; PriceLens NSIS 自定义片段
; 卸载时询问是否清理本地缓存与设置（%APPDATA%\pricelens）

!macro customUnInstall
  MessageBox MB_YESNO "是否同时删除 PriceLens 的本地缓存与设置数据？$\n（包括盯价任务、自定义脚本与价格缓存）" IDNO skipClean
    RMDir /r "$APPDATA\pricelens"
  skipClean:
!macroend
