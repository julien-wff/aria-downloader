// Android HTTP payload adapter. All calls belong to the libaria2 owner thread.
#ifndef ARIA_ANDROID_FD_H
#define ARIA_ANDROID_FD_H
#include <string>
extern "C" {
int aria_android_bind_fd(const char* path, int fd);
int aria_android_finish_fd(const char* path);
void aria_android_clear_fds();
}
namespace aria2 {
int androidPayloadFd(const std::string& path);
}
#endif
