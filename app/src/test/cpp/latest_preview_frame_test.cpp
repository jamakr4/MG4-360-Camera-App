#include "../../main/cpp/latest_preview_frame.h"
#include <cassert>
#include <chrono>
#include <future>

int main() {
    LatestPreviewFrame<int> mainDisplay(0), cluster(0);
    // The cluster does not consume anything: its entire backlog stays at one
    // latest frame while the other display keeps consuming every published frame.
    for (int i = 1; i <= 1000; ++i) {
        assert(cluster.submit(i, [i] { return i; }));
        assert(mainDisplay.submit(i, [i] { return i; }));
        int frame;
        assert(mainDisplay.take(frame) && frame == i);
    }
    int last;
    assert(cluster.take(last) && last == 1000);
    cluster.stop();
    assert(!cluster.submit(1001, [] { return 0; }));
    assert(mainDisplay.submit(1001, [] { return 1001; }));
    assert(mainDisplay.take(last) && last == 1001);

    LatestPreviewFrame<int> waiting(0);
    auto waiter = std::async(std::launch::async, [&] { int v; return waiting.take(v); });
    waiting.stop();
    assert(waiter.wait_for(std::chrono::seconds(1)) == std::future_status::ready);
    assert(!waiter.get());

    LatestPreviewFrame<int> throttled(50000);
    int copies = 0;
    assert(throttled.submit(100000, [&] { return ++copies; }));
    assert(!throttled.submit(120000, [&] { return ++copies; }));
    assert(copies == 1);
    assert(throttled.submit(150000, [&] { return ++copies; }));
    assert(throttled.take(last) && last == 2);

    // A 50 ms minimum gap on a 25 fps camera (40 ms period) kept only every
    // second frame. An unthrottled latest-frame mailbox must accept all 25.
    LatestPreviewFrame<int> camera25fps(0);
    for (int i = 0; i < 25; ++i) {
        assert(camera25fps.submit(i * 40000LL, [i] { return i; }));
        assert(camera25fps.take(last) && last == i);
    }
}
