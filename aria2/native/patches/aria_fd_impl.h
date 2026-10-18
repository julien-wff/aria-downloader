// Included once by File.cc; payloads keep logical private paths for control files.
#include "aria_fd.h"
#include <map>
#include <fcntl.h>
#include <sys/stat.h>
#include <unistd.h>
#include <cerrno>
namespace {
std::map<std::string, int> androidPayloads;
}
namespace aria2 {
int androidPayloadFd(const std::string& path) {
  auto it = androidPayloads.find(path);
  return it == androidPayloads.end() ? -1 : it->second;
}
}
extern "C" int aria_android_bind_fd(const char* path, int fd) {
  struct stat info;
  int flags = fcntl(fd, F_GETFL);
  if (!path || path[0] != '/' || flags < 0 || (flags & O_ACCMODE) != O_RDWR ||
      fstat(fd, &info) != 0 || !S_ISREG(info.st_mode) || lseek(fd, 0, SEEK_CUR) < 0 ||
      androidPayloads.count(path)) {
    errno = EINVAL;
    return -1;
  }
  int copy = fcntl(fd, F_DUPFD_CLOEXEC, 0);
  if (copy < 0) return -1;
  try { androidPayloads.emplace(path, copy); }
  catch (...) { close(copy); throw; }
  return 0;
}
extern "C" int aria_android_finish_fd(const char* path) {
  int fd = aria2::androidPayloadFd(path);
  if (fd < 0) { errno = EBADF; return -1; }
  int result;
  do { result = fsync(fd); } while (result < 0 && errno == EINTR);
  // Release even if flushing fails; the durable pending entry retains its bytes.
  close(fd);
  androidPayloads.erase(path);
  return result;
}
extern "C" void aria_android_clear_fds() {
  for (auto& entry : androidPayloads) close(entry.second);
  androidPayloads.clear();
}
