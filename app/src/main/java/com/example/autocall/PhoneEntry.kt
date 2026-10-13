package com.example.autocall

/**
 * 电话条目数据模型
 *
 * @param audioName 表格里填写的**原始**语音文件名。播放前用它按名重解析真实路径，
 *                  避免导入去重改名（`name_1.ext`）后与表格失联（修 B34/B8）。
 */
data class PhoneEntry(
    val phoneNumber: String,
    val contactName: String = "",
    val audioFilePath: String? = null,
    val accountNumber: String? = null,
    val balance: String? = null,
    val isCalled: Boolean = false,
    val callCount: Int = 0,
    val audioName: String? = null
)

/**
 * 通话记录数据模型（Task 2.5 扩展为全字段）。
 *
 * 所有新字段都带默认值，保证 Gson 反序列化旧数据时不炸。
 * `callStatus`/`resultCode` 存**枚举式稳定值**（如 `ok`/`fail`），不再存翻译后的文本（修 B26）。
 */
data class CallRecord(
    val callId: String = "",
    val seq: Int = 0,
    val phoneNumber: String = "",
    val contactName: String = "",
    val direction: String = "out",
    val simSlot: Int = 0,
    val callStatus: String = "",
    val resultCode: String = "fail",
    val callDuration: Long = 0,             // 真实通话时长（秒，接通→挂断）
    val dialDuration: Long = 0,             // 总拨打耗时（秒，拨号→挂断，修 B42 区分两个时长）
    val timestamp: String = "",
    val connectedAtMs: Long = 0,
    val recordFilePath: String? = null,
    val mediaStoreUri: String? = null,
    val recordMode: String = "NONE",
    val injectionFilePath: String? = null,
    val fileSizeBytes: Long = 0,
    val errorMessage: String? = null
)
