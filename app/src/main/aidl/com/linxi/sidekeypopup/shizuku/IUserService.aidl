package com.linxi.sidekeypopup.shizuku;

import com.linxi.sidekeypopup.shizuku.IKeyCallback;

/**
 * 跑在 shell 进程里的用户服务。
 * destroy 的 transaction code 是 Shizuku 约定的，不能改。
 */
interface IUserService {
    void destroy() = 16777114;
    void exit() = 1;
    void start(String devicePath, IKeyCallback callback) = 2;
    void stop() = 3;
    String describe() = 4;

    /**
     * 用户服务那边现在在盯哪些设备。
     *
     * 设备发现是在那个进程里做的，应用进程看不到；不给个回传的口子的话，
     * 「来源显示 Shizuku 但按了没反应」就完全没法查了。
     */
    String status() = 5;
}
