/* nocturne-attach（Unix 侧）：Linux / macOS 的 HotSpot attach 通道（自研实现）。
 *
 * 机制（与 HotSpot 发起端行为对齐，不复制其源码文本）：
 *  1. 目标 JVM 的 attach 监听器在 <tmpdir>/.java_pid<pid> 上监听 Unix 域套接字；
 *  2. 发起端 connect 该套接字，按线协议写入
 *     [协议版本, 动词, arg0, arg1, arg2] 这 5 个 NUL 结尾的字符串；
 *  3. 目标执行操作并把 [外层返回码] 与（load 时的）内层结果写回同一连接。
 *
 * 本文件只做传输层：connect / write-all / read / close。协议字节与结果解释全部在
 * Java 侧（{@link AttachProtocol} 与策略类），因此这里没有任何协议常量。
 *
 * 构建（Gradle 在 Linux/macOS 上现地编译，产物打进 core jar 的
 * /native/<os>-<arch>/nocturne-attach.{so,dylib}）：
 *   cc -shared -fPIC -O2 -I"$JAVA_HOME/include" -I"$JAVA_HOME/include/{linux,darwin}" \
 *      attach_unix.c -o nocturne-attach.so
 */

#include <jni.h>
#include <errno.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/un.h>

/* 与 Windows 侧同一口径：所有入口返回「非负成功 / 负 errno」，
 * Java 侧负责把返回码翻译成 IOException 文本，原生层不抛 Java 异常。 */
static jint negErrno(int code) {
    return (jint)(-(code == 0 ? EIO : code));
}

/* 向套接字写一段字节。Linux 用 MSG_NOSIGNAL、macOS 用 SO_NOSIGPIPE，
 * 否则对端已关闭时本进程会被 SIGPIPE 直接杀掉。 */
static ssize_t sendNoSignal(int fd, const void* buffer, size_t length) {
#if defined(MSG_NOSIGNAL)
    return send(fd, buffer, length, MSG_NOSIGNAL);
#else
    return send(fd, buffer, length, 0);
#endif
}

/* ---- PosixAttachNative ---- */

/* 连到目标的 attach 套接字。
 * 返回 fd（>= 0）；失败返回 -errno。
 * 连接前校验：确实是 socket 文件、且属于本进程的 euid（与 HotSpot 发起端一致，
 * 避免连到别的用户/普通文件；这也是 JDK 侧 "PID mismatch or permissions" 的来源）。 */
JNIEXPORT jint JNICALL
Java_dev_nocturne_core_attach_PosixAttachNative_nConnect(
        JNIEnv* env, jclass ignored, jstring path) {
    (void)ignored;
    if (path == NULL) {
        return negErrno(EINVAL);
    }
    const char* utf = (*env)->GetStringUTFChars(env, path, NULL);
    if (utf == NULL) {
        return negErrno(ENOMEM);
    }
    struct sockaddr_un addr;
    memset(&addr, 0, sizeof(addr));
    addr.sun_family = AF_UNIX;
#ifdef __APPLE__
    /* BSD 系（macOS）的 sockaddr_un 多一个 sun_len：不填会被内核拒绝。 */
    addr.sun_len = (unsigned char)sizeof(addr);
#endif
    size_t pathLength = strlen(utf);
    jint result;
    if (pathLength == 0 || pathLength >= sizeof(addr.sun_path)) {
        result = negErrno(ENAMETOOLONG);
    } else {
        memcpy(addr.sun_path, utf, pathLength + 1);
        struct stat info;
        if (stat(addr.sun_path, &info) != 0) {
            result = negErrno(errno);
        } else if (!S_ISSOCK(info.st_mode)) {
            result = negErrno(ENOTSOCK);
        } else if (info.st_uid != geteuid()) {
            result = negErrno(EACCES);
        } else {
            int fd = socket(AF_UNIX, SOCK_STREAM, 0);
            if (fd < 0) {
                result = negErrno(errno);
            } else {
                int connected;
                do {
                    connected = connect(fd, (struct sockaddr*)&addr, (socklen_t)sizeof(addr));
                } while (connected != 0 && errno == EINTR);
                if (connected != 0) {
                    result = negErrno(errno);
                    close(fd);
                } else {
                    result = (jint)fd;
                }
            }
        }
    }
    (*env)->ReleaseStringUTFChars(env, path, utf);
    return result;
}

/* 写完整段请求。返回 0 表示全部写出，否则 -errno。 */
JNIEXPORT jint JNICALL
Java_dev_nocturne_core_attach_PosixAttachNative_nWrite(
        JNIEnv* env, jclass ignored, jint fd, jbyteArray buffer,
        jint offset, jint length) {
    (void)ignored;
    if (fd < 0 || buffer == NULL || offset < 0 || length <= 0) {
        return negErrno(EINVAL);
    }
    jsize arrayLength = (*env)->GetArrayLength(env, buffer);
    if (offset >= arrayLength) {
        return negErrno(EINVAL);
    }
    if ((jlong)offset + (jlong)length > (jlong)arrayLength) {
        length = (jint)(arrayLength - offset);
    }
    jbyte* bytes = (*env)->GetByteArrayElements(env, buffer, NULL);
    if (bytes == NULL) {
        return negErrno(ENOMEM);
    }
    jint written = 0;
    jint result = 0;
    while (written < length) {
        ssize_t n = sendNoSignal(fd, bytes + offset + written,
                (size_t)(length - written));
        if (n > 0) {
            written += (jint)n;
        } else if (n < 0 && errno == EINTR) {
            continue;
        } else {
            result = (n < 0) ? negErrno(errno) : negErrno(EIO);
            break;
        }
    }
    (*env)->ReleaseByteArrayElements(env, buffer, bytes, JNI_ABORT);
    return result;
}

/* 读一段回复：>0 为读到的字节数，0 为对端关闭（EOF），<0 为 -errno。
 * 调用方按「EOF 即读完」循环调用（与 Unix 域套接字 attach 的单次响应语义一致）。 */
JNIEXPORT jint JNICALL
Java_dev_nocturne_core_attach_PosixAttachNative_nRead(
        JNIEnv* env, jclass ignored, jint fd, jbyteArray buffer,
        jint offset, jint length) {
    (void)ignored;
    if (fd < 0 || buffer == NULL || offset < 0 || length <= 0) {
        return negErrno(EINVAL);
    }
    jsize arrayLength = (*env)->GetArrayLength(env, buffer);
    if (offset >= arrayLength) {
        return negErrno(EINVAL);
    }
    if ((jlong)offset + (jlong)length > (jlong)arrayLength) {
        length = (jint)(arrayLength - offset);
    }
    unsigned char* staging = (unsigned char*)malloc((size_t)length);
    if (staging == NULL) {
        return negErrno(ENOMEM);
    }
    ssize_t n;
    do {
        n = read(fd, staging, (size_t)length);
    } while (n < 0 && errno == EINTR);
    if (n < 0) {
        jint result = negErrno(errno);
        free(staging);
        return result;
    }
    if (n > 0) {
        (*env)->SetByteArrayRegion(env, buffer, offset, (jsize)n, (jbyte*)staging);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            free(staging);
            return negErrno(EIO);
        }
    }
    free(staging);
    return (jint)n;
}

/* 关闭连接（失败不报告：关闭的失败无法让调用方做任何补救）。 */
JNIEXPORT void JNICALL
Java_dev_nocturne_core_attach_PosixAttachNative_nClose(
        JNIEnv* env, jclass ignored, jint fd) {
    (void)env;
    (void)ignored;
    if (fd >= 0) {
        close(fd);
    }
}
