# 创建子领地

## 创建方法

创建方法与普通领地相同，可以使用自动创建，也可以手动创建。

命令分别为：

`/dominion create_sub <子领地名称> [父领地名称]`

`/dominion auto_create_sub <子领地名称> [父领地名称]`

当不填写父领地名称时会尝试以当前所在领地为父领地进行创建。

## 权限

当玩家处在一个子领地内时，其行为只收到子领地的权限控制。
子领地的权限设置与父领地完全相同，参考[权限管理](permission/README.md)。

## 关于子领地嵌套

子领地内部可以再创建子领地，但是子领地的嵌套深度是有限制的，具体嵌套深度由服务器管理员在[配置文件](../operator/config.md)中设置。