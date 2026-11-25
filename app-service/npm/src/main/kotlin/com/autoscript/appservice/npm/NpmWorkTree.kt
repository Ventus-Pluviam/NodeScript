package com.autoscript.appservice.npm

import com.autoscript.domain.core.AutojsException
import com.autoscript.domain.core.ErrorCode
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.COPY_ATTRIBUTES

/** 迁移要验证 npm 重算的结果，不能先把唯一的真树交给 reify。普通增量安装仍走 rename。 */
internal fun copyNpmWorkTree(source: Path, target: Path) {
    if (Files.notExists(source, NOFOLLOW_LINKS)) return
    if (!Files.isDirectory(source, NOFOLLOW_LINKS)) {
        throw AutojsException(ErrorCode.ERR_IO, "node_modules 不是普通目录：$source")
    }
    Files.walk(source).use { paths ->
        paths.forEach { from ->
            val to = target.resolve(source.relativize(from))
            if (Files.isSymbolicLink(from)) copyLocalLink(from, to, target)
            else Files.copy(from, to, COPY_ATTRIBUTES)
        }
    }
}

/** 保留 npm .bin 等树内相对链接，但验证期间不能让 npm 经链接改到真树或工作区外。 */
private fun copyLocalLink(from: Path, to: Path, target: Path) {
    val link = Files.readSymbolicLink(from)
    if (link.isAbsolute || !to.parent.resolve(link).normalize().startsWith(target.normalize())) {
        throw AutojsException(ErrorCode.ERR_NOT_SUPPORTED, "依赖树含指向外部的链接，未执行迁移：$from")
    }
    Files.createSymbolicLink(to, link)
}
