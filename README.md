# XWiki Platform

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

### 1. 构建

```bash
cd xwiki-platform
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml \
  -pl xwiki-platform-core/xwiki-platform-oldcore -am \
  -Dxwiki.spoon.skip=true -Dlicense.skip=true -DskipTests=true -Dxwiki.checkstyle.skip=true install

cp xwiki-platform-core/xwiki-platform-oldcore/target/xwiki-platform-oldcore-18.1.0.jar \
   <tomcat>/webapps/xwiki/WEB-INF/lib/
```

### 2. 配置 xwiki.cfg

在 `<tomcat>/webapps/xwiki/WEB-INF/xwiki.cfg` 中添加：

```properties
# OA 单点登录密钥
xwiki.authentication.oa.key=<OA系统分配给你们的密钥>
```

### 3. 注册 Servlet

在 `<tomcat>/webapps/xwiki/WEB-INF/web.xml` 的 `</web-app>` 之前添加：

```xml
<!-- OA SSO login servlet -->
<servlet>
  <servlet-name>OALoginServlet</servlet-name>
  <servlet-class>com.xpn.xwiki.web.OALoginAction</servlet-class>
  <load-on-startup>0</load-on-startup>
</servlet>
<servlet-mapping>
  <servlet-name>OALoginServlet</servlet-name>
  <url-pattern>/oa-login</url-pattern>
</servlet-mapping>
```

### 4. 重启 Tomcat

```bash
<tomcat>/bin/shutdown.sh
<tomcat>/bin/startup.sh
```

### 5. 在 OA 系统中配置回调地址

```
http://<your-server>/xwiki/oa-login?pid=<应用ID>&userLoginId=<工号>&timestamp=<时间戳>&sign=<MD5签名>
```

### 6. 验证

用命令行生成测试签名：

```bash
# 替换成实际的 KEY
echo -n "1000admin$(date +%s%3N)你的OA密钥" | md5
```

浏览器访问（替换 timestamp 和 sign 为上面计算的值）：

```
http://localhost:8080/xwiki/oa-login?pid=1000&userLoginId=admin&timestamp=<时间戳>&sign=<MD5值>
```

如果能自动跳转到 XWiki 首页，说明部署成功。