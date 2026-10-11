// Copyright 2026 The RVPN Browser Authors
// Use of this source code is governed by a BSD-style license that can be
// found in the LICENSE file.

#ifndef CHROME_BROWSER_NET_RVPN_PROXY_CONFIG_SERVICE_H_
#define CHROME_BROWSER_NET_RVPN_PROXY_CONFIG_SERVICE_H_

#include <memory>
#include <string>

#include "base/files/file_path.h"
#include "base/timer/timer.h"
#include "net/proxy_resolution/proxy_config_service.h"

namespace net {
class ProxyConfigWithAnnotation;
}  // namespace net

// RVPN Browser: makes Chromium honour the proxy of the built-in RVPN engine.
//
// The Android RVPN UI (and, on desktop, the RVPN extension) runs the RVPN core
// engine, which exposes a loopback SOCKS5 proxy. On desktop the extension sets
// that proxy through chrome.proxy; on Android extensions are unavailable, so
// this service watches a small JSON file written by the RVPN UI inside the
// browser's data directory:
//
//   <app data dir>/rvpn/proxy.json
//   {"enabled": true, "host": "127.0.0.1", "port": 38271,
//    "bypass": ["localhost", "127.0.0.1", "[::1]"]}
//
// While "enabled" is true the RVPN proxy wins; while it is false (or the file
// is missing/being written) every call is delegated to the system service, so
// a browser that never uses RVPN behaves exactly like upstream Chromium.
class RvpnProxyConfigService : public net::ProxyConfigService {
 public:
  explicit RvpnProxyConfigService(
      std::unique_ptr<net::ProxyConfigService> system_service);

  RvpnProxyConfigService(const RvpnProxyConfigService&) = delete;
  RvpnProxyConfigService& operator=(const RvpnProxyConfigService&) = delete;

  ~RvpnProxyConfigService() override;

  // Location of the file written by the RVPN UI. Both sides derive it from the
  // app data directory (PathUtils.getDataDirectory() in Java,
  // base::DIR_ANDROID_APP_DATA in C++).
  static base::FilePath GetConfigFilePath();

  // net::ProxyConfigService:
  void AddObserver(Observer* observer) override;
  void RemoveObserver(Observer* observer) override;
  ConfigAvailability GetLatestProxyConfig(
      net::ProxyConfigWithAnnotation* config) override;
  void OnLazyPoll() override;

 private:
  void Poll();
  bool ReadConfigFile(net::ProxyConfigWithAnnotation* config);

  std::unique_ptr<net::ProxyConfigService> system_service_;
  base::RepeatingTimer poll_timer_;

  // Contents of the last successfully parsed file, used to detect changes.
  std::string last_contents_;
  bool rvpn_active_ = false;
  net::ProxyConfigWithAnnotation rvpn_config_;

  base::ObserverList<Observer>::Unchecked observers_;
};

#endif  // CHROME_BROWSER_NET_RVPN_PROXY_CONFIG_SERVICE_H_
