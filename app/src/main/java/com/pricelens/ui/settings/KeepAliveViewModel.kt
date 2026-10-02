package com.pricelens.ui.settings

import android.os.Build
import androidx.lifecycle.ViewModel
import com.pricelens.keepalive.Rom
import com.pricelens.keepalive.RomDetector
import com.pricelens.worker.WatchCheckRunner
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.StateFlow

/**
 * 保活引导页状态：
 *  - [rom]：ROM 识别结果（进入页面时判定一次，只影响显示哪段步骤表与跳转候选）；
 *  - [lastRound]：本进程最近一轮盯价的内存快照（WatchCheckRunner，与盯价页同一来源）。
 *
 * 不新造持久化：「前台服务 / WorkManager 是否在跑」没有公开查询接口，页面只如实转述
 * 现有信号；lastRound 为 null 时页面照实说「本进程还没跑过」，不许假装能判。
 */
@HiltViewModel
class KeepAliveViewModel @Inject constructor(runner: WatchCheckRunner) : ViewModel() {

    val rom: Rom = RomDetector.detect(Build.MANUFACTURER, Build.BRAND)

    val lastRound: StateFlow<WatchCheckRunner.RoundSummary?> = runner.lastRound
}
