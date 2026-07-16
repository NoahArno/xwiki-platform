# XWiki Platform

-Dxwiki.spoon.skip=true -Dlicense.skip=true -DskipTests=true -Dxwiki.checkstyle.skip=true install -f pom.xml

[XWiki Platform](https://www.xwiki.org/xwiki/bin/view/Documentation/) is a generic wiki platform offering runtime services for applications built on top of it.

XWiki Commons, XWiki Rendering, and XWiki Platform are part of the [XWiki.org](http://www.xwiki.org/) software forge. They are released together and share the same version.

## Documentation
* [API](https://www.xwiki.org/xwiki/bin/view/Documentation/DevGuide/API/)
* [Development Zone](https://dev.xwiki.org/xwiki/bin/view/Community/)
* Guides:
  * [Developer Guide](https://www.xwiki.org/xwiki/bin/view/Documentation/DevGuide/)
  * [Administrator Guide](https://www.xwiki.org/xwiki/bin/view/Documentation/AdminGuide/)
  * [User Guide](https://www.xwiki.org/xwiki/bin/view/Documentation/UserGuide/GettingStarted/)

## Download
Read our [Download and Installation](https://www.xwiki.org/xwiki/bin/view/Download/) instructions.

## Release Notes
Read our [Release Notes](https://www.xwiki.org/xwiki/bin/view/ReleaseNotes/).

## Tools
* [Continuous Integration](https://ci.xwiki.org/) setup launches a build for each commit.
* [Issue Tracker](https://jira.xwiki.org/browse/XWIKI), if you want to report an issue.
* [Development Flow](https://dev.xwiki.org/xwiki/bin/view/Community/DevelopmentPractices#HGeneralDevelopmentFlow) to see the full list of tools we use to build the XWiki software.
* [![Revved up by Develocity](https://img.shields.io/badge/Revved%20up%20by-Develocity-06A0CE?logo=Gradle&labelColor=02303A)](https://ge.xwiki.org/scans)

## Project Statistics

![XWiki Platform Activity](https://repobeats.axiom.co/api/embed/7d0980aec51d3e1b8622875db877a3eaacabe169.svg "XWiki Platform Activity")

Note that we're [using an issue tracker](https://jira.xwiki.org/browse/XWIKI) other than GitHub Issues (hence the empty left column in the stats).

See the [Project Health page](https://dev.xwiki.org/xwiki/bin/view/Community/ProjectHealth) for more statistics about the XWiki project.

## Community

We're always looking for contributors!

You should read our [Get Involved Guide](https://dev.xwiki.org/xwiki/bin/view/Community/Contributing) or get in touch:
* [Forum](https://dev.xwiki.org/xwiki/bin/view/Community/Discuss)
* [Chat](https://dev.xwiki.org/xwiki/bin/view/Community/Chat)

You can follow the XWiki news in [our blog](https://www.xwiki.org/xwiki/bin/view/Blog/).

Thank you to all contributors:

<a href="https://github.com/xwiki/xwiki-platform/graphs/contributors">
  <img src="https://contrib.rocks/image?repo=xwiki/xwiki-platform&max=5000" />
</a>

## OA 单点登录

1、配置 xwiki.cfg

```text
# OA 单点登录密钥
xwiki.authentication.oa.key=<OA系统分配给你们的密钥>
```

2、在 OA 系统中配置回调地址

```text
http://<your-server>/xwiki/bin/oalogin/?pid=<应用ID>&userLoginId=<工号>&timestamp=<时间戳>&sign=<MD5签名>
```

3、验证

用浏览器直接访问测试 URL（手动构造合法签名）：

# 用命令行生成测试签名，替换成你实际的 KEY 和参数
echo -n "1000admin$(date +%s%3N)你的OA密钥" | md5

然后访问：
http://localhost:8080/xwiki/bin/oalogin/?pid=1000&userLoginId=admin&timestamp=<上面用的时间戳>&sign=<上面算出来的md5>

如果能自动跳转到 XWiki 首页，说明部署成功。