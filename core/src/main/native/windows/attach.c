/* nocturne-attach: Windows 自实现 attach 原生层（自研重写，仅行为对齐 HotSpot）。
 *
 * 职责（与 OpenJDK 发起端等价，但全部自研实现）：
 *  1. openProcess/closeProcess：按 pid 打开/关闭目标进程句柄；
 *  2. createPipeServer：建本次命令专用的服务端命名管道；
 *  3. enqueueOperation：经远线程桩把 attach 操作送进目标 JVM；
 *  4. awaitConnection/readPipe/closePipe：服务端管道 IO。
 *
 * 构建：MSVC x64，需要 JDK 的 jni.h：
 *  `cl /LD /I"%JAVA_HOME%\include" /I"%JAVA_HOME%\include\win32" attach.c`
 * 产物放 `core/src/main/resources/native/windows-x64/nocturne-attach.dll`
 * （运行时由 NativeLibraryLoader 解压加载）。
 *
 * 许可注意：本文件为自研实现，不得复制 OpenJDK 源码文本；
 * 只对齐可观察行为（管道名规则、远线程入队语义、返回码）。
 */
#include <windows.h>
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <tlhelp32.h>
/* ---- 远线程桩（x64，自包含机器码） ----
 *
 * 语义：桩被注入目标进程后执行一次，按 Win64 调用约定调用目标 jvm.dll 的
 * JVM_EnqueueOperation(cmd, arg0, arg1, arg2, pipename)，然后退出线程。
 *
 * 操作块布局（目标进程内存，由发起端构造并写入；所有指针都是目标内的绝对地址）：
 *   +0x00 uint64 入队函数地址（发起端解析后回填）
 *   +0x08 uint64 cmd          -> JVM_EnqueueOperation 第 1 参（rcx）
 *   +0x10 uint64 arg0                                   第 2 参（rdx）
 *   +0x18 uint64 arg1                                   第 3 参（r8）
 *   +0x20 uint64 arg2                                   第 4 参（r9）
 *   +0x28 uint64 pipename                               第 5 参（栈，[rsp+0x20]）
 *   +0x30 起：上述字符串的内容（NUL 结尾 ANSI）
 *
 * Win64 ABI：前 4 个整型/指针参数走 rcx/rdx/r8/r9，第 5 个压栈（位于 32 字节
 * shadow space 之上，即 [rsp+0x20]）；调用点 rsp 必须 16 字节对齐。线程入口
 * 处 rsp ≡ 8 (mod 16)，故先 sub rsp,0x38（56 ≡ 8 mod 16）使调用点对齐。
 */
static const unsigned char kEnqueueStubX64[] = {
    0x4C, 0x8B, 0xD1,                   /* mov  r10, rcx            ; r10 = 操作块 */
    0x48, 0x83, 0xEC, 0x38,             /* sub  rsp, 0x38           ; 栈帧 + 对齐 */
    0x49, 0x8B, 0x42, 0x28,             /* mov  rax, [r10+0x28]     ; pipename */
    0x48, 0x89, 0x44, 0x24, 0x20,       /* mov  [rsp+0x20], rax     ; 第 5 个参数 */
    0x49, 0x8B, 0x02,                   /* mov  rax, [r10]          ; 入队函数地址 */
    0x49, 0x8B, 0x4A, 0x08,             /* mov  rcx, [r10+0x08]     ; cmd */
    0x49, 0x8B, 0x52, 0x10,             /* mov  rdx, [r10+0x10]     ; arg0 */
    0x4D, 0x8B, 0x42, 0x18,             /* mov  r8,  [r10+0x18]     ; arg1 */
    0x4D, 0x8B, 0x4A, 0x20,             /* mov  r9,  [r10+0x20]     ; arg2 */
    0xFF, 0xD0,                         /* call rax                 ; 投递操作 */
    0x48, 0x83, 0xC4, 0x38,             /* add  rsp, 0x38 */
    0xC3                                /* ret                      ; 线程函数返回 */
};

/* 桩长度（供 VirtualAlloc/WriteProcessMemory 用）。 */
static const SIZE_T kEnqueueStubLen = sizeof(kEnqueueStubX64);

/* 工具：按 pid 打开进程（远线程注入所需最小集）。 */
static HANDLE OpenTarget(DWORD pid) {
    return OpenProcess(PROCESS_QUERY_INFORMATION | PROCESS_CREATE_THREAD
        | PROCESS_VM_OPERATION | PROCESS_VM_WRITE | PROCESS_VM_READ,
        FALSE, pid);
}

/* 工具：jstring → UTF-16 缓冲（调用方负责 free）。失败返回 NULL。 */
static WCHAR* JstringToUtf16(JNIEnv* env, jstring value, jsize* outLen) {
    if (value == NULL) {
        return NULL;
    }
    jsize len = (*env)->GetStringLength(env, value);
    WCHAR* buffer = (WCHAR*)malloc(((size_t)len + 1) * sizeof(WCHAR));
    if (buffer == NULL) {
        return NULL;
    }
    const jchar* chars = (*env)->GetStringChars(env, value, NULL);
    if (chars == NULL) {
        free(buffer);
        return NULL;
    }
    for (jsize i = 0; i < len; i++) {
        buffer[i] = (WCHAR)chars[i];
    }
    buffer[len] = 0;
    (*env)->ReleaseStringChars(env, value, chars);
    if (outLen != NULL) {
        *outLen = len;
    }
    return buffer;
}

/* 工具：jbyteArray → 原生缓冲（调用方负责 free）。失败返回 NULL。 */
static unsigned char* JbytesToNative(JNIEnv* env, jbyteArray value, jsize* outLen) {
    if (value == NULL) {
        return NULL;
    }
    jsize len = (*env)->GetArrayLength(env, value);
    unsigned char* buffer = (unsigned char*)malloc(len > 0 ? (size_t)len : 1);
    if (buffer == NULL) {
        return NULL;
    }
    if (len > 0) {
        (*env)->GetByteArrayRegion(env, value, 0, len, (jbyte*)buffer);
        if ((*env)->ExceptionCheck(env)) {
            (*env)->ExceptionClear(env);
            free(buffer);
            return NULL;
        }
    }
    if (outLen != NULL) {
        *outLen = len;
    }
    return buffer;
}

/* ---- WindowsAttachNative ---- */

JNIEXPORT jlong JNICALL
Java_dev_nocturne_core_attach_WindowsAttachNative_nOpenProcess(
        JNIEnv* env, jclass ignored, jint pid) {
    (void)env; (void)ignored;
    if (pid <= 0) {
        return 0;
    }
    HANDLE handle = OpenTarget((DWORD)pid);
    if (handle == NULL) {
        return 0;
    }
    return (jlong)(intptr_t)handle;
}

JNIEXPORT void JNICALL
Java_dev_nocturne_core_attach_WindowsAttachNative_nCloseProcess(
        JNIEnv* env, jclass ignored, jlong handle) {
    (void)env; (void)ignored;
    if (handle != 0) {
        CloseHandle((HANDLE)(intptr_t)handle);
    }
}

JNIEXPORT jlong JNICALL
Java_dev_nocturne_core_attach_WindowsAttachNative_nCreatePipeServer(
        JNIEnv* env, jclass ignored, jstring pipeName) {
    jsize nameLen = 0;
    WCHAR* name = JstringToUtf16(env, pipeName, &nameLen);
    if (name == NULL || nameLen == 0) {
        free(name);
        return 0;
    }
    /* 单实例、字节流、阻塞模式：目标连入一次、读完回复即关，符合单命令语义。 */
    HANDLE pipe = CreateNamedPipeW(name,
        PIPE_ACCESS_INBOUND | FILE_FLAG_OVERLAPPED,
        PIPE_TYPE_BYTE | PIPE_READMODE_BYTE | PIPE_WAIT,
        1,          /* 单实例：本次命令专用 */
        4096, 4096,
        0, NULL);
    free(name);
    if (pipe == INVALID_HANDLE_VALUE) {
        return 0;
    }
    return (jlong)(intptr_t)pipe;
}

/* ---- NativePipeIo ---- */

JNIEXPORT jint JNICALL
Java_dev_nocturne_core_attach_NativePipeIo_nAwaitConnection(
        JNIEnv* env, jclass ignored, jlong pipeHandle, jlong timeoutMs) {
    (void)env; (void)ignored;
    HANDLE pipe = (HANDLE)(intptr_t)pipeHandle;
    if (pipe == NULL || pipe == INVALID_HANDLE_VALUE) {
        return 6; /* ERROR_INVALID_HANDLE */
    }
    /* 重叠 Connect + 等待：超时可控，避免目标不连时发起端永久阻塞。 */
    OVERLAPPED overlapped;
    memset(&overlapped, 0, sizeof(overlapped));
    overlapped.hEvent = CreateEventW(NULL, TRUE, FALSE, NULL);
    if (overlapped.hEvent == NULL) {
        return (jint)GetLastError();
    }
    BOOL connected = ConnectNamedPipe(pipe, &overlapped);
    DWORD error = connected ? 0 : GetLastError();
    DWORD waitResult = WAIT_OBJECT_0;
    if (!connected && error == ERROR_IO_PENDING) {
        DWORD timeout = timeoutMs < 0 ? INFINITE : (timeoutMs > 60000 ? 60000 : (DWORD)timeoutMs);
        waitResult = WaitForSingleObject(overlapped.hEvent, timeout);
        if (waitResult == WAIT_OBJECT_0) {
            DWORD transferred = 0;
            if (!GetOverlappedResult(pipe, &overlapped, &transferred, FALSE)) {
                error = GetLastError();
            } else {
                error = 0;
            }
        } else {
            CancelIo(pipe);
            error = (waitResult == WAIT_TIMEOUT) ? 1460 /* ERROR_TIMEOUT */ : GetLastError();
        }
    } else if (connected) {
        error = 0;
    } else if (error == ERROR_PIPE_CONNECTED) {
        /* 目标在调用前已连入：视为成功。 */
        error = 0;
    }
    CloseHandle(overlapped.hEvent);
    return (jint)error;
}

JNIEXPORT jint JNICALL
Java_dev_nocturne_core_attach_NativePipeIo_nReadPipe(
        JNIEnv* env, jclass ignored, jlong pipeHandle,
        jbyteArray buffer, jint offset, jint length) {
    HANDLE pipe = (HANDLE)(intptr_t)pipeHandle;
    if (pipe == NULL || pipe == INVALID_HANDLE_VALUE) {
        return -6;
    }
    if (buffer == NULL || offset < 0 || length <= 0) {
        return -87; /* ERROR_INVALID_PARAMETER 取反 */
    }
    jsize arrayLen = (*env)->GetArrayLength(env, buffer);
    if (offset >= arrayLen) {
        return -87;
    }
    if ((int64_t)offset + (int64_t)length > (int64_t)arrayLen) {
        length = (jint)(arrayLen - offset);
    }
    unsigned char* staging = (unsigned char*)malloc((size_t)length);
    if (staging == NULL) {
        return -8; /* ERROR_NOT_ENOUGH_MEMORY 取反 */
    }
    DWORD got = 0;
    BOOL ok = ReadFile(pipe, staging, (DWORD)length, &got, NULL);
    if (!ok) {
        DWORD error = GetLastError();
        free(staging);
        if (error == ERROR_BROKEN_PIPE) {
            return 0; /* 对端关闭 = EOF，Java 侧按 -1 处理 */
        }
        return -(jint)(error == 0 ? 1 : error);
    }
    if (got == 0) {
        free(staging);
        return 0;
    }
    (*env)->SetByteArrayRegion(env, buffer, offset, (jsize)got, (jbyte*)staging);
    free(staging);
    if ((*env)->ExceptionCheck(env)) {
        (*env)->ExceptionClear(env);
        return -1;
    }
    return (jint)got;
}

JNIEXPORT void JNICALL
Java_dev_nocturne_core_attach_NativePipeIo_nClosePipe(
        JNIEnv* env, jclass ignored, jlong pipeHandle) {
    (void)env; (void)ignored;
    if (pipeHandle != 0) {
        HANDLE pipe = (HANDLE)(intptr_t)pipeHandle;
        DisconnectNamedPipe(pipe);
        CloseHandle(pipe);
    }
}

/* 分页读：跨页保护边界时 ReadProcessMemory 可能只返回部分字节（ERROR_PARTIAL_COPY），
 * 此时按已读长度继续读剩余部分，而不是直接判失败。 */
static BOOL ReadFull(HANDLE process, uint64_t address, void* buffer, SIZE_T size) {
    SIZE_T total = 0;
    while (total < size) {
        SIZE_T done = 0;
        if (!ReadProcessMemory(process, (LPCVOID)(uintptr_t)(address + total),
                (unsigned char*)buffer + total, size - total, &done)) {
            if (done == 0) {
                return FALSE;
            }
        }
        if (done == 0) {
            return FALSE;
        }
        total += done;
    }
    return TRUE;
}

/* 工具：在目标进程 jvm.dll 里解析 JVM_EnqueueOperation 地址（发起端本地完成）。
 * 任一步失败返回 0；仅支持 x64，32 位目标返回 0（不做跨位宽注入）。 */
static uint64_t ResolveEnqueueAddress(HANDLE process, DWORD pid) {
    HANDLE snapshot = CreateToolhelp32Snapshot(TH32CS_SNAPMODULE | TH32CS_SNAPMODULE32,
        pid);
    if (snapshot == INVALID_HANDLE_VALUE) {
        fprintf(stderr, "[attach-resolve] snapshot failed err=%lu\n", GetLastError());
        return 0;
    }
    MODULEENTRY32W entry;
    entry.dwSize = sizeof(entry);
    uint64_t jvmBase = 0;
    if (Module32FirstW(snapshot, &entry)) {
        do {
            const WCHAR* name = wcsrchr(entry.szModule, L'\\');
            name = (name == NULL) ? entry.szModule : name + 1;
            if (_wcsicmp(name, L"jvm.dll") == 0) {
                jvmBase = (uint64_t)(uintptr_t)entry.modBaseAddr;
                break;
            }
        } while (Module32NextW(snapshot, &entry));
    } else {
        fprintf(stderr, "[attach-resolve] Module32First failed err=%lu\n", GetLastError());
    }
    CloseHandle(snapshot);
    if (jvmBase == 0) {
        fprintf(stderr, "[attach-resolve] jvm.dll not found\n");
        return 0;
    }
    IMAGE_DOS_HEADER dos;
    if (!ReadFull(process, jvmBase, &dos, sizeof(dos))
            || dos.e_magic != IMAGE_DOS_SIGNATURE) {
        fprintf(stderr, "[attach-resolve] dos read failed err=%lu magic=%x\n",
            GetLastError(), dos.e_magic);
        return 0;
    }
    DWORD ntOffset = (DWORD)dos.e_lfanew;
    DWORD signature = 0;
    if (!ReadFull(process, jvmBase + ntOffset, &signature, sizeof(signature))
            || signature != IMAGE_NT_SIGNATURE) {
        fprintf(stderr, "[attach-resolve] sig read failed err=%lu sig=%x\n",
            GetLastError(), signature);
        return 0;
    }
    /* FileHeader 紧跟在 Signature 之后：Machine 位于 ntOffset+4（WORD）。
     * 曾误写成 ntOffset+6，读到的是 NumberOfSections，machine 校验必然失败。 */
    WORD machine = 0;
    if (!ReadFull(process, jvmBase + ntOffset + sizeof(DWORD),
            &machine, sizeof(machine))) {
        fprintf(stderr, "[attach-resolve] machine read failed err=%lu\n", GetLastError());
        return 0;
    }
    if (machine != IMAGE_FILE_MACHINE_AMD64) {
        fprintf(stderr, "[attach-resolve] machine mismatch %x\n", machine);
        return 0;
    }
    IMAGE_DATA_DIRECTORY exportDir;
    memset(&exportDir, 0, sizeof(exportDir));
    /* PE32+ 可选头内导出表目录项偏移：Signature(4) + FileHeader(20) + 到 DataDirectory(112)；
     * 注意 PE32 是 +96，PE32+（x64）是 +112，用错会读到全零/垃圾。 */
    DWORD exportDirOffset = ntOffset + 4 + 20 + 112;
    if (!ReadFull(process, jvmBase + exportDirOffset, &exportDir, sizeof(exportDir))
            || exportDir.VirtualAddress == 0) {
        fprintf(stderr, "[attach-resolve] datadir read failed err=%lu rva=%lx size=%lx off=%lx base=%llx\n",
            GetLastError(), (unsigned long)exportDir.VirtualAddress,
            (unsigned long)exportDir.Size, (unsigned long)exportDirOffset,
            (unsigned long long)jvmBase);
        return 0;
    }
    IMAGE_EXPORT_DIRECTORY exports;
    memset(&exports, 0, sizeof(exports));
    if (!ReadFull(process, jvmBase + exportDir.VirtualAddress, &exports, sizeof(exports))
            || exports.NumberOfNames == 0
            || exports.NumberOfNames > 8192) {
        fprintf(stderr, "[attach-resolve] export dir read failed err=%lu names=%lu\n",
            GetLastError(), (unsigned long)exports.NumberOfNames);
        return 0;
    }
    DWORD nameCount = exports.NumberOfNames;
    DWORD* nameRVAs = (DWORD*)malloc(nameCount * sizeof(DWORD));
    WORD* ordinals = (WORD*)malloc(nameCount * sizeof(WORD));
    DWORD* funcRVAs = (DWORD*)malloc(exports.NumberOfFunctions * sizeof(DWORD));
    uint64_t result = 0;
    if (nameRVAs == NULL || ordinals == NULL || funcRVAs == NULL) {
        goto resolveOut;
    }
    if (!ReadFull(process, jvmBase + exports.AddressOfNames,
            nameRVAs, nameCount * sizeof(DWORD))) {
        goto resolveOut;
    }
    if (!ReadFull(process, jvmBase + exports.AddressOfNameOrdinals,
            ordinals, nameCount * sizeof(WORD))) {
        goto resolveOut;
    }
    if (!ReadFull(process, jvmBase + exports.AddressOfFunctions,
            funcRVAs, exports.NumberOfFunctions * sizeof(DWORD))) {
        goto resolveOut;
    }
    {
        static const char kTarget[] = "JVM_EnqueueOperation";
        for (DWORD i = 0; i < nameCount; i++) {
            char name[64];
            memset(name, 0, sizeof(name));
            if (!ReadFull(process, jvmBase + nameRVAs[i], name, sizeof(name) - 1)) {
                continue;
            }
            if (strcmp(name, kTarget) != 0) {
                continue;
            }
            WORD ordinal = ordinals[i];
            if ((DWORD)ordinal >= exports.NumberOfFunctions) {
                break;
            }
            DWORD funcRVA = funcRVAs[ordinal];
            if (funcRVA >= exportDir.VirtualAddress
                    && funcRVA < exportDir.VirtualAddress + exportDir.Size) {
                break;
            }
            result = jvmBase + funcRVA;
            break;
        }
        if (result == 0) {
            fprintf(stderr, "[attach-resolve] symbol %s missing among %lu names\n",
                kTarget, (unsigned long)nameCount);
        }
    }
resolveOut:
    free(nameRVAs);
    free(ordinals);
    free(funcRVAs);
    return result;
}

/* 工具：把 AttachProtocol 请求（连续 NUL 结尾 UTF-8 字符串）切成若干段。
 * 返回切出的段数（含末尾空串），最多 maxParts 段。 */
static int SplitProtocolParts(const unsigned char* request, DWORD requestLen,
        const char** parts, int maxParts) {
    int count = 0;
    DWORD i = 0;
    while (i < requestLen && count < maxParts) {
        parts[count++] = (const char*)(request + i);
        while (i < requestLen && request[i] != 0) {
            i++;
        }
        i++; /* 跳过该段的结尾 NUL */
    }
    return count;
}

/* 工具：UTF-16 → UTF-8/ANSI 缓冲（调用方负责 free）。失败返回 NULL。 */
static char* Utf16ToAnsi(const WCHAR* wide) {
    int len = WideCharToMultiByte(CP_UTF8, 0, wide, -1, NULL, 0, NULL, NULL);
    if (len <= 0) {
        return NULL;
    }
    char* out = (char*)malloc((size_t)len);
    if (out == NULL) {
        return NULL;
    }
    if (WideCharToMultiByte(CP_UTF8, 0, wide, -1, out, len, NULL, NULL) == 0) {
        free(out);
        return NULL;
    }
    return out;
}

/* 工具：在目标进程分配可执行内存并写入桩 + 操作块。
 * 操作块布局见文件头 kEnqueueStubX64 的说明；request 的 5 段按
 * [协议版本, cmd, arg0, arg1, arg2] 解释，协议版本段不进操作块
 * （Windows 的 JVM_EnqueueOperation 只接受 cmd/arg0/arg1/arg2/pipename）。
 * 成功返回目标内桩地址，否则返回 NULL（调用方负责清理已分配内存）。 */
static LPVOID WriteStubAndBlock(HANDLE process, const unsigned char* request,
        DWORD requestLen, const char* pipeAnsi, SIZE_T* outBlockSize) {
    const char* parts[5];
    if (SplitProtocolParts(request, requestLen, parts, 5) < 5) {
        return NULL; /* 协议不完整：宁可不注入，也不传半截参数 */
    }
    const char* values[5];
    values[0] = parts[1]; /* cmd：load */
    values[1] = parts[2]; /* arg0：agent jar */
    values[2] = parts[3]; /* arg1：agent 参数 */
    values[3] = parts[4]; /* arg2：空串 */
    values[4] = pipeAnsi; /* pipename */
    SIZE_T lengths[5];
    SIZE_T stringsLen = 0;
    for (int i = 0; i < 5; i++) {
        lengths[i] = strlen(values[i]) + 1;
        stringsLen += lengths[i];
    }
    const SIZE_T headerSize = 0x30;
    SIZE_T blockSize = headerSize + stringsLen;
    SIZE_T total = ((blockSize + 15) / 16) * 16 + kEnqueueStubLen + 16;
    LPVOID remote = VirtualAllocEx(process, NULL, total,
        MEM_COMMIT | MEM_RESERVE, PAGE_EXECUTE_READWRITE);
    if (remote == NULL) {
        return NULL;
    }
    unsigned char* stubAt = (unsigned char*)remote;
    /* 操作块起点按 16 字节对齐，指针值是目标内绝对地址，因此块内偏移必须固定。 */
    unsigned char* blockAt = stubAt + kEnqueueStubLen + 16;
    SIZE_T written = 0;
    if (!WriteProcessMemory(process, stubAt, kEnqueueStubX64, kEnqueueStubLen, &written)
            || written != kEnqueueStubLen) {
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        return NULL;
    }
    unsigned char* block = (unsigned char*)calloc(1, blockSize);
    if (block == NULL) {
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        return NULL;
    }
    /* +0x00 留作入队函数地址占位（调用方解析后回填），+0x08 起是 5 个参数指针。 */
    SIZE_T stringOffset = 0;
    for (int i = 0; i < 5; i++) {
        uint64_t target = (uint64_t)(uintptr_t)(blockAt + headerSize + stringOffset);
        memcpy(block + 8 + (SIZE_T)i * 8, &target, sizeof(target));
        memcpy(block + headerSize + stringOffset, values[i], lengths[i]);
        stringOffset += lengths[i];
    }
    BOOL ok = WriteProcessMemory(process, blockAt, block, blockSize, &written);
    free(block);
    if (!ok || written != blockSize) {
        VirtualFreeEx(process, remote, 0, MEM_RELEASE);
        return NULL;
    }
    if (outBlockSize != NULL) {
        *outBlockSize = total;
    }
    return stubAt;
}

/* ---- enqueue（远线程投递） ----
 *
 * 步骤（行为对齐 HotSpot 发起端）：
 *  1. 在目标 jvm.dll 里解析入队函数地址（枚举目标模块 + GetProcAddress 语义，
 *     由发起端经 Toolhelp + 导出表读取实现，不注入代码）；
 *  2. 分配远内存、写入桩 + 操作块（含请求字节与管道名），回填入队函数地址；
 *  3. CreateRemoteThread 启动桩，等待它执行完（10s 上限）；
 *  4. 清理远内存与线程句柄，返回投递结果。
 */
JNIEXPORT jint JNICALL
Java_dev_nocturne_core_attach_WindowsAttachNative_nEnqueueOperation(
        JNIEnv* env, jclass ignored, jlong handle,
        jbyteArray request, jstring pipeName) {
    HANDLE process = (HANDLE)(intptr_t)handle;
    if (process == NULL) {
        return 6;
    }
    DWORD pid = GetProcessId(process);
    if (pid == 0) {
        return 6;
    }
    /* 1. 解析目标 jvm.dll 里 JVM_EnqueueOperation 的绝对地址 */
    uint64_t enqueueAddr = ResolveEnqueueAddress(process, pid);
    if (enqueueAddr == 0) {
        return 127; /* ERROR_PROC_NOT_FOUND：目标无 jvm.dll 或非 x64 */
    }
    jsize requestLen = 0;
    unsigned char* requestBytes = JbytesToNative(env, request, &requestLen);
    if (requestBytes == NULL || requestLen <= 0) {
        free(requestBytes);
        return 87;
    }
    jsize pipeLen = 0;
    WCHAR* pipeChars = JstringToUtf16(env, pipeName, &pipeLen);
    if (pipeChars == NULL || pipeLen == 0) {
        free(requestBytes);
        free(pipeChars);
        return 87;
    }
    /* JVM_EnqueueOperation 的 pipename 形参是窄字符 char*（HotSpot 侧用 CreateFileA
     * 打开），不是宽字符：这里转成 UTF-8 再进操作块。 */
    char* pipeAnsi = Utf16ToAnsi(pipeChars);
    free(pipeChars);
    if (pipeAnsi == NULL) {
        free(requestBytes);
        return 87;
    }
    /* 2. 分配远内存、写入桩 + 操作块 */
    SIZE_T total = 0;
    LPVOID stubAt = WriteStubAndBlock(process, requestBytes, (DWORD)requestLen,
        pipeAnsi, &total);
    free(requestBytes);
    free(pipeAnsi);
    if (stubAt == NULL) {
        return 8;
    }
    /* 3. 回填入队函数地址到操作块 offset 0 */
    unsigned char* blockAt = (unsigned char*)stubAt + kEnqueueStubLen + 16;
    SIZE_T written = 0;
    if (!WriteProcessMemory(process, blockAt, &enqueueAddr, sizeof(enqueueAddr),
            &written)
            || written != sizeof(enqueueAddr)) {
        VirtualFreeEx(process, stubAt, 0, MEM_RELEASE);
        return 8;
    }
    /* 4. 远线程启动桩（rcx = 操作块地址），10s 上限等待执行完 */
    HANDLE thread = CreateRemoteThread(process, NULL, 0,
        (LPTHREAD_START_ROUTINE)stubAt, blockAt, 0, NULL);
    if (thread == NULL) {
        VirtualFreeEx(process, stubAt, 0, MEM_RELEASE);
        return (jint)GetLastError();
    }
    DWORD waitResult = WaitForSingleObject(thread, 10000);
    DWORD exitCode = 0;
    if (waitResult == WAIT_OBJECT_0) {
        GetExitCodeThread(thread, &exitCode);
    }
    CloseHandle(thread);
    VirtualFreeEx(process, stubAt, 0, MEM_RELEASE);
    if (waitResult != WAIT_OBJECT_0) {
        return 1460; /* ERROR_TIMEOUT：目标 attach 监听器 10s 内未执行操作 */
    }
    return 0;
}
