// Copyright 2026 The RVPN Browser Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#include "chrome/browser/net/rvpn_proxy_config_service.h"

#include <utility>

#include "base/files/file_util.h"
#include "base/functional/bind.h"
#include "base/json/json_reader.h"
#include "base/logging.h"
#include "base/path_service.h"
#include "base/strings/string_number_conversions.h"
#include "base/strings/string_util.h"
#include "base/task/sequenced_task_runner.h"
#include "base/time/time.h"
#include "base/values.h"
#include "net/base/traffic_annotation.h"
#include "net/proxy_resolution/proxy_config.h"
#include "net/proxy_resolution/proxy_config_with_annotation.h"

namespace {

// How often the configuration file is checked. The file is tiny and is only
// written when the user toggles RVPN mode, so polling is both cheaper and far
// more robust than a cross-process notification mechanism.
constexpr base::TimeDelta kPollInterval = base::Seconds(1);

const char kRvpnConfigDir[] = "rvpn";
const char kRvpnConfigFile[] = "proxy.json";

}  // namespace

RvpnProxyConfigService::RvpnProxyConfigService(
    std::unique_ptr<net::ProxyConfigService> system_service)
    : system_service_(std::move(system_service)) {
  rvpn_config_ = net::ProxyConfigWithAnnotation::CreateDirect();
  // Read once synchronously so the very first network request already sees the
  // correct configuration after a browser restart in RVPN mode.
  Poll();
  poll_timer_.Start(FROM_HERE, kPollInterval,
                    base::BindRepeating(&RvpnProxyConfigService::Poll,
                                        base::Unretained(this)));
}

RvpnProxyConfigService::~RvpnProxyConfigService() = default;

// static
base::FilePath RvpnProxyConfigService::GetConfigFilePath() {
  base::FilePath data_dir;
  if (!base::PathService::Get(base::DIR_ANDROID_APP_DATA, &data_dir)) {
    return base::FilePath();
  }
  return data_dir.AppendASCII(kRvpnConfigDir).AppendASCII(kRvpnConfigFile);
}

void RvpnProxyConfigService::AddObserver(Observer* observer) {
  observers_.AddObserver(observer);
  if (system_service_) {
    system_service_->AddObserver(observer);
  }
}

void RvpnProxyConfigService::RemoveObserver(Observer* observer) {
  observers_.RemoveObserver(observer);
  if (system_service_) {
    system_service_->RemoveObserver(observer);
  }
}

void RvpnProxyConfigService::OnLazyPoll() {
  Poll();
  if (system_service_) {
    system_service_->OnLazyPoll();
  }
}

net::ProxyConfigService::ConfigAvailability
RvpnProxyConfigService::GetLatestProxyConfig(
    net::ProxyConfigWithAnnotation* config) {
  if (rvpn_active_) {
    *config = rvpn_config_;
    return CONFIG_VALID;
  }
  if (system_service_) {
    return system_service_->GetLatestProxyConfig(config);
  }
  *config = net::ProxyConfigWithAnnotation::CreateDirect();
  return CONFIG_VALID;
}

void RvpnProxyConfigService::Poll() {
  net::ProxyConfigWithAnnotation candidate =
      net::ProxyConfigWithAnnotation::CreateDirect();
  const bool active = ReadConfigFile(&candidate);
  if (!active && !rvpn_active_) {
    return;  // nothing to do, keep delegating to the system service
  }
  if (active && rvpn_active_ &&
      candidate.value().Equals(rvpn_config_.value())) {
    return;
  }

  rvpn_active_ = active;
  rvpn_config_ = candidate;
  for (Observer& observer : observers_) {
    observer.OnProxyConfigChanged(rvpn_config_, CONFIG_VALID);
  }
}

bool RvpnProxyConfigService::ReadConfigFile(
    net::ProxyConfigWithAnnotation* config) {
  const base::FilePath path = GetConfigFilePath();
  if (path.empty()) {
    return false;
  }

  std::string contents;
  if (!base::ReadFileToString(path, &contents)) {
    last_contents_.clear();
    return false;
  }
  if (contents == last_contents_) {
    return rvpn_active_;
  }
  last_contents_ = contents;

  std::optional<base::Value> parsed = base::JSONReader::Read(contents);
  if (!parsed || !parsed->is_dict()) {
    LOG(WARNING) << "RVPN: ignoring malformed proxy configuration";
    return false;
  }
  const base::DictValue& dict = parsed->GetDict();

  if (!dict.FindBool("enabled").value_or(false)) {
    return false;
  }
  const std::string* host = dict.FindString("host");
  std::optional<int> port = dict.FindInt("port");
  if (!host || host->empty() || !port || *port <= 0 || *port > 65535) {
    LOG(WARNING) << "RVPN: proxy configuration is incomplete";
    return false;
  }

  net::ProxyConfig proxy_config;
  // "socks5://host:port" is the same rule string the desktop extension uses.
  proxy_config.proxy_rules().ParseFromString(
      "socks5://" + *host + ":" + base::NumberToString(*port));
  proxy_config.proxy_rules().bypass_rules.Clear();

  const base::ListValue* bypass = dict.FindList("bypass");
  if (bypass) {
    for (const base::Value& entry : *bypass) {
      if (!entry.is_string()) {
        continue;
      }
      std::string pattern;
      base::TrimWhitespaceASCII(entry.GetString(), base::TRIM_ALL, &pattern);
      if (!pattern.empty()) {
        proxy_config.proxy_rules().bypass_rules.AddRuleFromString(pattern);
      }
    }
  }
  if (proxy_config.proxy_rules().bypass_rules.IsEmpty()) {
    proxy_config.proxy_rules().bypass_rules.AddRuleFromString("localhost");
    proxy_config.proxy_rules().bypass_rules.AddRuleFromString("127.0.0.1");
  }
  proxy_config.set_auto_detect(false);

  *config = net::ProxyConfigWithAnnotation(proxy_config,
                                           MISSING_TRAFFIC_ANNOTATION);
  VLOG(1) << "RVPN: proxy set to socks5://" << *host << ":" << *port;
  return true;
}
