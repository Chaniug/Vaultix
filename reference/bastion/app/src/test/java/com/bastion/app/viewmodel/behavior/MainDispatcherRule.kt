package com.bastion.app.viewmodel.behavior

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.rules.TestWatcher
import org.junit.runner.Description

/**
 * 把 `Dispatchers.Main` 换成测试调度器的 JUnit Rule。
 *
 * `PasswordViewModel` 的 `init` 块与所有 `fun xxx()` 公开入口都通过 `viewModelScope.launch`
 * 派发协程，而 `viewModelScope` 绑定的是 `Dispatchers.Main.immediate`。在纯 JVM 单元测试中
 * 该调度器没有 Android 主线程可用，直接构造 ViewModel 会抛
 * `IllegalStateException: Module with the Main dispatcher had failed to initialize`。
 *
 * 这里默认用 [UnconfinedTestDispatcher]：`viewModelScope.launch { ... }` 会在调用处**同步**
 * 执行完毕，因此测试可以在调用 `viewModel.deletePasswordEntry(entry)` 之后立刻断言仓库交互，
 * 无需额外 `advanceUntilIdle()`。这正是编排类行为测试想要的语义。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {

    override fun starting(description: Description) {
        Dispatchers.setMain(dispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
