package com.xixin.codent.domain.common

/**
 * 统一的结果类型
 * 🔥 替代原来的字符串错误返回
 */
sealed class Result<out T> {
    data class Success<out T>(val data: T) : Result<T>()
    data class Error(val message: String, val exception: Throwable? = null) : Result<Nothing>()
    object Loading : Result<Nothing>()
}

// 扩展函数，方便处理结果
inline fun <T, R> Result<T>.fold(
    onSuccess: (T) -> R,
    onError: (String, Throwable?) -> R
): R {
    return when (this) {
        is Result.Success -> onSuccess(data)
        is Result.Error -> onError(message, exception)
        Result.Loading -> onError("加载中", null)
    }
}

inline fun <T> Result<T>.onSuccess(action: (T) -> Unit): Result<T> {
    if (this is Result.Success) action(data)
    return this
}

inline fun <T> Result<T>.onError(action: (String, Throwable?) -> Unit): Result<T> {
    if (this is Result.Error) action(message, exception)
    return this
}
