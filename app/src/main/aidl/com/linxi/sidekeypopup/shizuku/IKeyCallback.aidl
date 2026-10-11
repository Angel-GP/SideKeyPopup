package com.linxi.sidekeypopup.shizuku;

/** 用户服务把按键事件推回应用进程用的回调。 */
oneway interface IKeyCallback {
    void onKey(int action, int code, long eventTimeMs);
}
