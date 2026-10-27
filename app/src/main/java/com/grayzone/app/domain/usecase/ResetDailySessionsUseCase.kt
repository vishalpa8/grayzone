package com.grayzone.app.domain.usecase

import android.content.Context
import android.content.SharedPreferences
import com.grayzone.app.LogComponent
import com.grayzone.app.GrayzoneLogger
import com.grayzone.app.PrefsKeys
import com.grayzone.app.data.RuntimeSessionReset
import com.grayzone.app.data.StreakEvaluator
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ResetDailySessionsUseCase @Inject constructor(
    private val prefs: SharedPreferences
) {
    suspend fun invoke(context: Context): Boolean {
        if (!RuntimeSessionReset.resetIfNeeded(prefs)) return false
        
        GrayzoneLogger.i(LogComponent.OVERLAY, "Midnight reset: session locks cleared for the new day")
        
        try {
            StreakEvaluator.run(context)
        } catch (e: Exception) {
            GrayzoneLogger.w(LogComponent.OVERLAY, "Midnight streak catch-up failed", e)
        }
        
        return true
    }
}
