#include "../../main/cpp/piece_overview.h"
#include <cassert>
#include <numeric>

int main() {
    assert(pieceOverview("", 8, 1024).empty());
    assert(pieceOverview("x", 0, 1024).empty());
    assert(pieceOverview("x", 1, 0).empty());
    assert(pieceOverview("x", 9, 1024).empty());
    const auto ordered = pieceOverview(std::string("\x81\xff", 2), 9, 1024);
    assert(ordered.size() == 20);
    assert(ordered[2] == 1 && ordered[4] == 0 && ordered[16] == 1 && ordered[18] == 1);
    const auto grouped = pieceOverview(std::string(126, '\xff'), 1001, 1ULL << 33);
    assert(grouped.size() == 514 && grouped[1] == (1LL << 33));
    int64_t completed = 0, total = 0;
    for (size_t i = 2; i < grouped.size(); i += 2) {
        completed += grouped[i]; total += grouped[i + 1];
        assert(grouped[i + 1] == 3 || grouped[i + 1] == 4);
    }
    assert(completed == 1001 && total == 1001); // Ignore trailing spare bits.
    const auto empty = pieceOverview(std::string(126, '\0'), 1001, 1024);
    for (size_t i = 2; i < empty.size(); i += 2) assert(empty[i] == 0);
}
