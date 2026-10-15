#pragma once

#include <stddef.h>
#include <stdint.h>

#define CAN_RECORD_FLAG_EXT 0x01
#define CAN_RECORD_FLAG_RTR 0x02

// One CAN frame as read from the TWAI driver, with the metadata that must be
// captured at reception time (sequence number and timestamp).
struct CanRecord {
  int64_t  tsUs;      // esp_timer_get_time() right after the TWAI read
  uint32_t seq;       // frame counter (gap = lost frames)
  uint32_t id;
  uint8_t  flags;     // CAN_RECORD_FLAG_*
  uint8_t  dlc;
  uint8_t  data[8];
};

// Fixed-size circular buffer. When full, push() overwrites the oldest record.
// Not thread-safe: push/peek/pop must be called from the same task (loop()).
template <size_t N>
class CanRingBuffer {
public:
  void push(const CanRecord& rec) {
    mBuffer[mHead] = rec;
    mHead = (mHead + 1) % N;
    if (mCount == N) {
      mTail = (mTail + 1) % N;  // full: drop the oldest record
    } else {
      mCount++;
    }
  }

  // Copy the oldest record into 'out' without removing it.
  bool peek(CanRecord& out) const {
    if (mCount == 0) return false;
    out = mBuffer[mTail];
    return true;
  }

  // Remove the oldest record.
  void pop() {
    if (mCount == 0) return;
    mTail = (mTail + 1) % N;
    mCount--;
  }

  bool   empty() const { return mCount == 0; }
  size_t size() const { return mCount; }

private:
  CanRecord mBuffer[N];
  size_t    mHead{0};   // next write position
  size_t    mTail{0};   // oldest record
  size_t    mCount{0};
};
