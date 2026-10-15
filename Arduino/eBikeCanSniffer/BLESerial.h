#pragma once

#include <atomic>
#include <functional>

#include <BLEDevice.h>
#include <BLEServer.h>
#include <BLEUtils.h>
#include <BLE2902.h>

// BLE Serial based on Nordic UART Service (NUS):
// https://docs.nordicsemi.com/r/bundle/sdk_nrf5_v16.0.0/page/ble_sdk_app_nus_eval.html
class BLESerial : public BLEServerCallbacks, public BLECharacteristicCallbacks {
public:
  using ReceiveCallback = std::function<void(const String&)>;

  BLESerial(const String& deviceName = "BLESerial", uint16_t desiredMtu = 247);

  void begin(ReceiveCallback receiveCallback = nullptr); 
  void send(const String &msg);
  void send(const char* data, size_t len);

  bool isConnected() const { return mDeviceConnected; }

private:
  static constexpr const char* SERVICE_UUID           = "6E400001-B5A3-F393-E0A9-E50E24DCCA9E";
  static constexpr const char* CHARACTERISTIC_UUID_TX = "6E400003-B5A3-F393-E0A9-E50E24DCCA9E";
  static constexpr const char* CHARACTERISTIC_UUID_RX = "6E400002-B5A3-F393-E0A9-E50E24DCCA9E";

  // BLEServerCallbacks
  void onConnect(BLEServer* server) override;
  void onDisconnect(BLEServer* server) override;

  // BLECharacteristicCallbacks
  void onWrite(BLECharacteristic* characteristic) override;

  const String        mDeviceName;
  const uint16_t      mDesiredMtu;

  ReceiveCallback     mReceiveCallback{nullptr};
  BLEServer*          mServer{nullptr};
  BLECharacteristic*  mTxCharacteristic{nullptr};
  BLECharacteristic*  mRxCharacteristic{nullptr};

  std::atomic<bool>   mDeviceConnected{false};
};
