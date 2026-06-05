package com.mk65.adapter;

/**
 * 消息平台适配器接口。
 * 所有外部通信平台（QQ/Discord/Telegram等）需实现此接口。
 */
public interface Adapter {

    /** 启动适配器，建立连接 */
    void start();

    /** 停止适配器，断开连接 */
    void stop();

    /** 是否已连接 */
    boolean isConnected();

    /** 发送群聊消息 */
    void sendGroupMsg(long groupId, String message);

    /** 发送私聊消息 */
    void sendPrivateMsg(long userId, String message);

    /**
     * 注册消息回调。
     * 当适配器收到新消息时，通过此回调通知核心循环。
     * 回调参数: (sourceIdentifier, messageText)
     *   sourceIdentifier 格式: "qq_group:群号" 或 "qqid:用户QQ号"
     */
    void setMessageCallback(java.util.function.BiConsumer<String, String> callback);
}
