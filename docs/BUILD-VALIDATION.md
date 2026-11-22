# 仓库整理版构建验证

验证日期：2026-11-22。

- 从独立的 npm 锁文件安装依赖，构建 React 资源成功。
- Gradle Wrapper 8.14.3 构建 Koog debug APK 成功，输出 `BUILD SUCCESSFUL`。
- Vite 构建工具已从原快照的 8.0.14 更新到同主版本 8.3.8；最新依赖安装审计输出 `found 0 vulnerabilities`。该结果只代表检查当时已知的 npm 告警。
- 未修改原工程或向车机安装这个整理版 APK，也未重新运行云端语音或车机实物接口验收。
- 设备配置脚本已做 Python 语法检查；真实部署仍需提供自己的 HTTPS 网关配置。

| 测试类 | 方法数 | 失败 | 错误 |
|---|---:|---:|---:|
| cockpit.koog.KoogTest | 2 | 0 | 0 |
| cockpit.validation.Android13Test | 3 | 0 | 0 |

合计 5 个方法通过。测试包含模拟契约场景及框架恢复；不代表大模型理解或真人语音质量。

已核对上传清单：不含 API Key、真实访问配置、原部署 IP、设备序列号、个人路径、APK、构建缓存、原始调测日志和录音。
