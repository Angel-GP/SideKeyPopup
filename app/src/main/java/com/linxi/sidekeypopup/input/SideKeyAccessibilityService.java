package com.linxi.sidekeypopup.input;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;

import com.linxi.sidekeypopup.Config;

/**
 * 侧键识别的主力：无障碍服务。
 *
 * 开了 canRequestFilterKeyEvents 之后，onKeyEvent 能拿到侧键。
 * 关键点：**永远返回 false**，也就是只旁观、不消费。
 * 所以按住侧键时系统自己的长按功能照常工作，我们只负责在「快速点一下」时出手。
 */
public class SideKeyAccessibilityService extends AccessibilityService {

    private static final String TAG = "SideKeyPopup";

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        AccessibilityServiceInfo info = getServiceInfo();
        if (info != null) {
            info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS;
            setServiceInfo(info);
        }
        Log.i(TAG, "accessibility connected, key filtering on");
        SourceManager.get().setAccessibilityAvailable(this, true);
    }

    @Override
    public boolean onKeyEvent(KeyEvent event) {
        SideKeyEngine.get().onKey(
                event.getAction(),
                event.getKeyCode(),
                event.getScanCode(),
                event.getEventTime());
        // 绝不消费：长按要留给系统。
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        // 不需要窗口事件，配置里也把事件类型压到最小了。
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        Log.i(TAG, "accessibility disconnected");
        SourceManager.get().setAccessibilityAvailable(this, false);
        return super.onUnbind(intent);
    }

    /** 侧键码是不是我们要的那个（给日志用）。 */
    static boolean isSideKey(KeyEvent event) {
        return event.getKeyCode() == Config.KEY_CODE || event.getScanCode() == Config.KEY_CODE;
    }
}
