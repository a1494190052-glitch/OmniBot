package cn.com.omnimind.assists.api.bean

import android.graphics.Bitmap

data class CaptureData(
    val isSuccess: Boolean = false,//是否截图成功
    val isFilterOverlay: Boolean = false,//是否已经过滤掉overlay
    val isLotOfSingleColor: Boolean = false,//是否是大面积纯色
    val isMostlyLightBackground: Boolean = false,//是否大部分是亮色
    val isSideRegionMostlySingleColor: Boolean = false,//是否侧边区域大部分是纯色
    val imageFilePath: String? = null,//返回图片文件
    val imageBase64: String? = null,//返回图片base64
    val imageBitmap: Bitmap? = null,//返回图片bitmap
    // 压缩相关字段
    val originalWidth: Int = 0,           // 原始图片宽度
    val originalHeight: Int = 0,          // 原始图片高度
    val compressedWidth: Int = 0,         // 压缩后图片宽度（未压缩则等于原始）
    val compressedHeight: Int = 0,        // 压缩后图片高度
    val appliedScale: Float = 1f,         // 实际应用的缩放比例（未压缩则为1f）
    val errorCode: Int? = null,
    val errorMessage: String? = null,
) {
    /**
     * 将基于压缩图片的坐标缩放到原始分辨率
     * VLM 返回的坐标是基于压缩后图片的，需要放大到原始分辨率才能正确点击
     */
    fun scaleCoordinatesToOriginal(coords: Pair<Float, Float>): Pair<Float, Float> {
        return cn.com.omnimind.baselib.util.ImageCompressor.scaleCoordinatesToOriginal(coords, appliedScale)
    }
}
