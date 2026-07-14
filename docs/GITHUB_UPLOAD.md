# 将 ViewAbility 上传到 GitHub

当前项目已经完成本地 Git 初始化，默认分支为 `main`，并已经创建首次提交。由于还没有 GitHub 仓库地址，最后的远程绑定和推送需要在 GitHub 创建仓库后执行。

## 1. 在 GitHub 创建空仓库

建议仓库名称使用 `ViewAbility`，创建时不要勾选初始化 README、`.gitignore` 或 License，避免与本地已有文件产生首次合并冲突。

## 2. 绑定远程仓库

HTTPS 方式：

```bash
cd /Users/yangfu/sohu/work/ViewAbility
git remote add origin https://github.com/<你的用户名>/ViewAbility.git
```

SSH 方式：

```bash
cd /Users/yangfu/sohu/work/ViewAbility
git remote add origin git@github.com:<你的用户名>/ViewAbility.git
ssh -T git@github.com
```

如果本地已经存在 `origin`，使用下面的命令替换地址：

```bash
git remote set-url origin <你的仓库地址>
```

## 3. 推送 main 分支

```bash
cd /Users/yangfu/sohu/work/ViewAbility
git push -u origin main
```

首次 HTTPS 推送时，GitHub 通常需要 Personal Access Token，不能再使用账户密码；SSH 推送则需要本机已经配置 GitHub SSH 公钥。

## 4. 日常提交和同步

```bash
git status
git add .
git commit -m "描述本次修改"
git push
```

拉取远程最新代码：

```bash
git pull --rebase origin main
```

如果 GitHub 仓库创建时误初始化了 README，首次推送前可以先执行：

```bash
git pull --rebase origin main
git push -u origin main
```
