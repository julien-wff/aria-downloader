#include "../../../native/patches/aria_fd_impl.h"
#include <cassert>
#include <cstdlib>

int main() {
    char name[] = "/tmp/aria-http-fd-XXXXXX";
    int fd = mkstemp(name);
    assert(fd >= 0);
    const char* logical = "/private/task/payload";
    assert(aria_android_bind_fd(logical, fd) == 0);
    int mapped = aria2::androidPayloadFd(logical);
    assert(mapped >= 0 && mapped != fd);
    assert(aria_android_bind_fd(logical, fd) < 0);
    assert(pwrite(mapped, "xyz", 3, 4) == 3);
    char bytes[3];
    assert(pread(fd, bytes, 3, 4) == 3);
    assert(bytes[0] == 'x' && bytes[2] == 'z');
    assert(ftruncate(mapped, 5) == 0);
    struct stat info;
    assert(fstat(fd, &info) == 0 && info.st_size == 5);
    int readonly = open(name, O_RDONLY);
    assert(aria_android_bind_fd("/readonly", readonly) < 0);
    close(readonly);
    int pipes[2];
    assert(pipe(pipes) == 0);
    assert(aria_android_bind_fd("/pipe", pipes[1]) < 0);
    close(pipes[0]); close(pipes[1]);
    assert(aria_android_bind_fd("relative", fd) < 0);
    assert(aria_android_finish_fd(logical) == 0);
    assert(aria2::androidPayloadFd(logical) == -1);
    assert(fcntl(mapped, F_GETFD) == -1 && errno == EBADF);
    assert(fstat(fd, &info) == 0); // Caller retains ownership of its descriptor.
    assert(aria_android_finish_fd(logical) < 0);
    assert(aria_android_bind_fd(logical, fd) == 0);
    mapped = aria2::androidPayloadFd(logical);
    aria_android_clear_fds();
    assert(fcntl(mapped, F_GETFD) == -1 && errno == EBADF);
    assert(aria_android_bind_fd(logical, fd) == 0);
    close(aria2::androidPayloadFd(logical)); // Simulate an unusable backing descriptor.
    assert(aria_android_finish_fd(logical) < 0);
    assert(aria2::androidPayloadFd(logical) == -1);
    close(fd); unlink(name);
}
