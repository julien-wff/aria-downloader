#pragma once
#include <algorithm>
#include <cstdint>
#include <string>
#include <vector>

// libaria2 exposes a byte-string bitfield, most significant bit first.
inline std::vector<int64_t> pieceOverview(const std::string& bits, int count, uint64_t pieceBytes) {
    if (count <= 0 || pieceBytes == 0 || pieceBytes > INT64_MAX ||
        bits.size() < (static_cast<size_t>(count) + 7) / 8) return {};
    const int cells = std::min(count, 256);
    std::vector<int64_t> data{count, static_cast<int64_t>(pieceBytes)};
    data.reserve(2 + cells * 2);
    for (int cell = 0; cell < cells; ++cell) {
        const int begin = static_cast<int>(static_cast<int64_t>(cell) * count / cells);
        const int end = static_cast<int>(static_cast<int64_t>(cell + 1) * count / cells);
        int64_t done = 0;
        for (int piece = begin; piece < end; ++piece)
            if (static_cast<unsigned char>(bits[piece / 8]) & (0x80 >> (piece % 8))) ++done;
        data.push_back(done);
        data.push_back(end - begin);
    }
    return data;
}
