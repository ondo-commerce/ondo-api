package com.ondo.wholesale.chat.domain;

/** 방 목록 거르기 (MUL-158). UNREAD = unreadCount > 0, NEEDS_REPLY = needsReply. */
public enum ChatRoomFilter {
    UNREAD,
    NEEDS_REPLY
}
