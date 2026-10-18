package com.example.c001apk.adapter

import android.view.View
import android.widget.ImageView
import android.widget.Toast
import com.example.c001apk.logic.model.HomeFeedResponse
import com.example.c001apk.ui.app.AppActivity
import com.example.c001apk.ui.coolpic.CoolPicActivity
import com.example.c001apk.ui.feed.FeedActivity
import com.example.c001apk.ui.follow.FFFListActivity
import com.example.c001apk.ui.others.WebViewActivity
import com.example.c001apk.ui.topic.TopicActivity
import com.example.c001apk.ui.user.UserActivity
import com.example.c001apk.util.ClipboardUtil.copyText
import com.example.c001apk.util.ImageUtil
import com.example.c001apk.util.IntentUtil
import com.example.c001apk.util.NetWorkUtil.openLink
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.select.Elements

interface ItemListener {

    fun onFollowUser(uid: String, followAuthor: Int) {}

    fun onShowCollection(id: String, title: String) {}

    /**
     * 版本历史条目点击：拉该版本的下载直链，拿到后由 BaseAppFragment 弹下载框。
     * 参数都来自列表条目自身（/v6/apk/downloadVersionList 只下发这些字段，
     * 条目没有 id / entityId，所以不能走 onViewApk）。
     */
    fun onDownloadVersion(
        view: View,
        packageName: String?,
        versionCode: Long?,
        versionName: String?,
        size: String?,
    ) {
    }

    fun onViewApk(view: View, id: String?) {
        id?.let {
            IntentUtil.startActivity<AppActivity>(view.context) {
                putExtra("id", id)
            }
        }
    }

    fun onMessLongClicked(uname: String, id: String, position: Int): Boolean {
        return true
    }

    fun onMessClicked(view: View, note: String) {
        val doc: Document = Jsoup.parse(note)
        val links: Elements = doc.select("a[href]")
        links.forEach { link ->
            val href = link.attr("href")
            if (href.contains("/feed/")) {
                val id: String
                var rid: String? = null
                val index0 = href.replace("/feed/", "").indexOf('?')
                val index1 = href.indexOf("rid=")
                val index2 = href.indexOf('&')
                if (index0 != -1 && index1 != -1 && index2 != -1) {
                    id = href.replace("/feed/", "").substring(0, index0)
                    rid = href.substring(index1 + 4, index2)
                } else if (index0 != -1 && index1 != -1 && index2 == -1) {
                    id = href.replace("/feed/", "").substring(0, index0)
                    rid = href.substring(index1 + 4)
                } else id = href
                IntentUtil.startActivity<FeedActivity>(view.context) {
                    putExtra("viewReply", true)
                    putExtra("id", id)
                    putExtra("rid", rid)
                }
            } else if (href.contains("http")) {
                IntentUtil.startActivity<WebViewActivity>(view.context) {
                    putExtra("url", href)
                }
            } else if (href.isNullOrEmpty()) {
                return
            } else {
                Toast.makeText(view.context, "unknown type", Toast.LENGTH_SHORT)
                    .show()
                copyText(view.context, href)
            }
        }
    }

    fun showTotalReply(
        id: String,
        uid: String,
        position: Int,
        rPosition: Int?,
        intercept: Boolean = false
    ) {
    }

    fun viewFFFList(view: View, uid: String?, isEnable: Boolean, type: String) {
        uid?.let {
            IntentUtil.startActivity<FFFListActivity>(view.context) {
                putExtra("uid", uid)
                putExtra("isEnable", isEnable)
                putExtra("type", type)
            }
        }
    }

    fun loadImage(view: View, imageUrl: String?) {
        imageUrl?.let {
            ImageUtil.startBigImgViewSimple(view as ImageView, it)
        }
    }

    fun onViewCoolPic(view: View, title: String?) {
        IntentUtil.startActivity<CoolPicActivity>(view.context) {
            putExtra("title", title?.replace("#", ""))
        }
    }

    fun onViewTopic(view: View, type: String?, title: String?, url: String?, id: String?) {
        if (type == "topic" || type == "product" || url.isNullOrEmpty()) {
            IntentUtil.startActivity<TopicActivity>(view.context) {
                putExtra("type", type)
                putExtra("title", title)
                putExtra("url", url)
                putExtra("id", id)
            }
        } else {
            // 头条横向菜单（iconMiniScrollCard）除本机机型外的条目 entityType 都是 recentHistory，
            // 而 TopicActivity 只认 topic / product，遇到别的 type 会一直显示加载中（不发起任何请求）。
            // 这里按 url 走通用跳转：/u/ 用户、/t/ 话题、/product/ 机型、/apk/ 应用。
            openLink(view.context, url, title)
        }
    }

    fun onOpenLink(view: View, url: String?, title: String?) {
        url?.let {
            openLink(view.context, it, title)
        }
    }

    /**
     * [feedData] 只有 `item_home_feed` 这类手上真有整条动态的入口才传，详情页拿它先出首屏，
     * 再静默请求详情补全。列表不下的字段（`userAction.followAuthor`、`message_raw_output`、
     * `topReplyRows`）就靠这次补全，期间由 [FeedActivity] 侧转圈占位。
     *
     * 类型必须是 `Any?` 而不是 `HomeFeedResponse.Data?`：databinding 里没有整条动态的入口
     * 只能写 `null` 字面量，会被推断成 `java.lang.Object`，声明成具体类型会直接
     * "cannot find method onViewFeed" 编译失败。
     */
    fun onViewFeed(
        view: View, id: String?, uid: String?, username: String?, userAvatar: String?,
        deviceTitle: String?, message: String?, dateline: String?, rid: Any?, isViewReply: Any?,
        feedData: Any? = null
    ) {
        IntentUtil.startActivity<FeedActivity>(view.context) {
            putExtra("id", id)
            rid?.let {
                putExtra("rid", rid as String)
            }
            isViewReply?.let {
                putExtra("viewReply", it as Boolean)
            }
            (feedData as? HomeFeedResponse.Data)?.let {
                putExtra("feedData", it)
            }
        }
    }

    fun onViewUser(view: View, uid: String?) {
        IntentUtil.startActivity<UserActivity>(view.context) {
            putExtra("id", uid)
        }
    }

    /**
     * 长按复制。
     *
     * 原来是跳 `CopyActivity`（那边是个可选中的页面，要再手动全选+复制）；
     * 改成**直接复制到剪贴板**，跳过中间页。
     *
     * 这个方法被 **12 个布局**的 `android:onLongClick` 调用
     * （`item_feed_content` / `item_home_feed` / `item_home_feed_cover` /
     *  `item_feed_article_text` / `item_feed_reply` / `item_feed_content_reply_item` /
     *  `item_reply_to_reply_item` / `item_history_feed` / `item_message_content` …），
     * 改这一处就全部生效。
     *
     * `ClipboardUtil.copyText` 里已经做了「登记是自己复制的」
     * （`ShareLinkPromer.markSelfCopied`），所以切出去再回来不会被
     * 剪贴板识别当成外部分享链接再问一遍。
     */
    fun onCopyText(view: View, text: String?): Boolean {
        text?.let { copyText(view.context, it) }
        return true
    }

    fun onCopyToClip(view: View, text: String?) {
        text?.let {
            copyText(view.context, it)
        }
    }

    fun onExpand(
        view: View, id: String, uid: String,
        text: String?, position: Int, rPosition: Int?
    ) {
    }

    fun onLikeClick(type: String, id: String, isLike: Int) {}

    /**
     * 详情页底栏「收藏」：弹收藏夹选择。数字变化由实现方回写（服务端多收藏夹，
     * 这里不是简单的 +1/-1，收藏数得用接口返回的 favnum）。
     */
    fun onFavoriteClick(id: String?) {}

    /** 详情页底栏「转发」：拉系统分享面板发链接（不是转发动态，forwardnum 不变） */
    fun onShareFeed(id: String?) {}

    fun onReply(
        id: String,
        cuid: String,
        uid: String,
        username: String?,
        position: Int,
        rPosition: Int?
    ) {
    }

    fun onBlockUser(id: String, uid: String, position: Int) {}

    fun onDeleteClicked(entityType: String, id: String, position: Int) {}

    // 修改动态可见性（1 = 仅自己可见，0 = 公开）
    fun onChangePublishStatus(id: String, position: Int) {}

    // 个人主页置顶 / 取消置顶。isStickTop = 该条当前是否已置顶（true 则执行取消置顶）
    fun onChangeStickTop(id: String, isStickTop: Boolean, position: Int) {}
}