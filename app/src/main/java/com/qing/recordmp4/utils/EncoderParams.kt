package com.qing.recordmp4.utils

data class EncoderParams(
    val videoPath: String,
    val frameWidth: Int,     // 图像宽度
    val frameHeight: Int,    // 图像高度
    val bitRate: Int,
    val frameRate: Int,
    val isVertical: Boolean,

    val picPath: String,     // 图片抓拍路径
    val audioBitrate: Int,   // 音频编码比特率
    val audioChannelCount: Int, // 通道数据
    val audioSampleRate: Int,   // 采样率

    val audioChannelConfig: Int, // 单声道或立体声
    val audioFormat: Int,    // 采样精度
    val audioSource: Int,     // 音频来源
)
