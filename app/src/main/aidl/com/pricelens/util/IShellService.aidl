// IShellService.aidl —— Shizuku UserService 接口
// 由 Shizuku 以 ADB/shell 权限运行 ShizukuShellService 并回传 binder
package com.pricelens.util;

interface IShellService {
    String exec(String command);
    int destroy();
}
