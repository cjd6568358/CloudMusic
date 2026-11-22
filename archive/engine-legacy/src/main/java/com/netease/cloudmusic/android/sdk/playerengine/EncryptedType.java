package com.netease.cloudmusic.android.sdk.playerengine;

/**
 * 12 §50 真机 A3 · native 字段校准（jadx 1.5.1 / classes14.dex 实证）。
 * SourceConfig.encryptedType 的字段类型描述符，libncmplayerengine.so 用
 * GetFieldID 直接解析，类与枚举常量名必须与竞品一致。
 */
public enum EncryptedType {
    None,
    EncryptNCM,
    EncryptXor163,
    EncryptXorKey
}
