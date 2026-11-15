#include "../../main/cpp/camera_access_gate.h"
#include <cassert>
#include <thread>
#include <atomic>

int main() {
    CameraAccessGate gate;
    {
        CameraOpening opening(gate);
        assert(opening);
        gate.setExternalBlocked(true);
        assert(gate.snapshot().opening == 1);
        gate.opened();
        assert(gate.snapshot().devices == 1);
        gate.setOemBlocked(false);
        assert(gate.blocked());
        gate.closed();
    }
    assert(gate.snapshot().opening == 0 && gate.snapshot().devices == 0);
    { CameraOpening rejected(gate); assert(!rejected); }
    assert(gate.snapshot().opening == 0);
    gate.setOemBlocked(true);
    gate.setExternalBlocked(false);
    assert(gate.blocked());
    gate.setOemBlocked(false);
    {
        CameraOpening failedOpen(gate);
        assert(failedOpen);
    }
    assert(gate.snapshot().devices == 0 && gate.snapshot().opening == 0);

    // Reserve an open before the handoff; it must stay visible until it finishes.
    std::atomic<bool> reserved{false}, finish{false};
    std::thread opener([&] {
        CameraOpening opening(gate);
        assert(opening);
        reserved = true;
        while (!finish.load()) std::this_thread::yield();
    });
    while (!reserved.load()) std::this_thread::yield();
    gate.setExternalBlocked(true);
    assert(gate.snapshot().opening == 1);
    { CameraOpening rejected(gate); assert(!rejected); }
    finish = true;
    opener.join();
    assert(gate.snapshot().opening == 0 && gate.snapshot().devices == 0);
}
