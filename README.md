# 拖放增强 (DragDropPlus)

让 **任意应用** 都能使用 OriginOS 的超级拖放：长按文字/图片跨应用拖走。

## 原理

vivo 的「拖放直达」(`com.vivo.globaldragdrop`) 用一个白名单决定哪些 App 的哪些控件可以拖。
本模块 hook 白名单的最终裁决读点，让判定恒为通过：

```
za.n.p(...)                                        // 拖放调度协程
  └── EnhancedDragWhiteList$Companion.checkWhiteList(...) -> WhiteListResultBean
        └── IConfigListStrategy.executionStrategy(...)   // Strict / UnStrict / BlackList / NoWidgetLimit
```

调用点原 smali：

```smali
invoke checkWhiteList$default(...) -> WhiteListResultBean
move-result-object v0
invoke-virtual {v0}, ->getResult()Z
move-result v3
if-nez v3, :cond_616         # result==false
invoke-virtual {v0}, ->getBlackResult()Z
move-result v3
if-eqz v3, :cond_600         # blackResult==false -> 不通过
goto :goto_616               # blackResult==true  -> 通过
```

因此 hook `WhiteListResultBean.getResult()` 恒返回 `true` 即可全部放行。

**只改返回值，不改 bean 字段** —— `type` / `imageFormat` / `strictModel` 等
仍由 vivo 填写，保证后续抓取逻辑走正常分支。

## 使用

1. 安装 APK
2. LSPosed 启用模块，作用域勾选 **拖放直达** (`com.vivo.globaldragdrop`)
3. 强制停止拖放直达，或重启手机
4. 任意 App 长按文字/图片即可拖动

## 版本

- `version.properties` 控制 versionCode / versionName
- 每次改动 +1，便于在「设置 → 应用」确认版本

## 致谢

打点依据来自对 `拖放直达 4.1.0.0` 的静态分析。
