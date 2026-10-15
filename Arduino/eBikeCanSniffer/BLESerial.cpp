#include "BLESerial.h"

BLESerial::BLESerial(const String& deviceName, uint16_t desiredMtu) : mDeviceName(deviceName), mDesiredMtu(desiredMtu) {
}

void BLESerial::begin(ReceiveCallback receiveCallback) {
  mReceiveCallback = receiveCallback;

  BLEDevice::init(mDeviceName);
  BLEDevice::setMTU(mDesiredMtu);

  mServer = BLEDevice::createServer();
  mServer->setCallbacks(this);

  BLEService *service = mServer->createService(SERVICE_UUID);

  mTxCharacteristic = service->createCharacteristic(CHARACTERISTIC_UUID_TX, BLECharacteristic::PROPERTY_NOTIFY);
  mTxCharacteristic->addDescriptor(new BLE2902());

  mRxCharacteristic = service->createCharacteristic(CHARACTERISTIC_UUID_RX, BLECharacteristic::PROPERTY_WRITE);
  mRxCharacteristic->setCallbacks(this);

  service->start();

  BLEAdvertising *advertising = BLEDevice::getAdvertising();
  advertising->addServiceUUID(SERVICE_UUID);
  advertising->setScanResponse(true);
  advertising->start();

  Serial.printf("BLE: advertising name: %s\n", mDeviceName.c_str());
}

void BLESerial::send(const String &msg) {
  send(msg.c_str(), msg.length());
}

void BLESerial::send(const char* data, size_t len) {
  // No Serial.printf here: send() is called for every CAN frame and serial
  // output at 115200 baud would block the loop (lost frames).
  if (!mDeviceConnected || !mTxCharacteristic || !mServer) {
    return;
  }

  uint16_t connId = mServer->getConnId();

  // MTU
  uint16_t mtu = mServer->getPeerMTU(connId);
  if (mtu <= 3) {
    mtu = 23; // Use default value
  }

  // Chunk size
  size_t chunkSize = mtu - 3;
  if (chunkSize > 240) {
    chunkSize = 240;
  }

  // Message
  size_t offset = 0;

  while (offset < len) {
    size_t n = min(chunkSize, len - offset);
    mTxCharacteristic->setValue((uint8_t*)(data + offset), n);
    mTxCharacteristic->notify();
    offset += n;
    if (offset < len) {
      delay(3);
    }
  }
}

void BLESerial::onConnect(BLEServer* server) {
  if (mDeviceConnected) {
    Serial.printf("BLE: Rejecting extra client connection attempt\n");
    return;
  }

  mDeviceConnected = true;

  Serial.printf("BLE: client connected\n");
  server->getAdvertising()->stop();
}

void BLESerial::onDisconnect(BLEServer* server) {
  mDeviceConnected = false;

  Serial.printf("BLE: client disconnected. Restarting advertising...\n");
  server->getAdvertising()->start();
}

void BLESerial::onWrite(BLECharacteristic* characteristic) {
  if (characteristic != mRxCharacteristic) {
    Serial.printf("BLE: Unsupported RX characteristic");
    return;
  }

  const String& msg = characteristic->getValue();
  if (msg.length() == 0) {
    Serial.printf("BLE: Empty message received\n");
    return;
  }

  Serial.printf("MSG: %s", msg);

  if (mReceiveCallback) {
    mReceiveCallback(msg);
  }
}
