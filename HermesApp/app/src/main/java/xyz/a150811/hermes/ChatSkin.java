package xyz.a150811.hermes;

import org.json.JSONObject;

/**
 * Injects a WeChat-like conversation skin into the embedded Hermes web UI:
 * gray chat surface, green right-aligned user bubbles, white assistant
 * bubbles, avatars beside the bubbles. Pure CSS so Vue re-renders keep it.
 */
final class ChatSkin {
    private static final String CSS = """
            html.hermes-wx .chat-main{margin:0!important;border:0!important;border-radius:0!important;box-shadow:none!important;background:#ededed!important}
            html.hermes-wx .chat-content-wrapper,
            html.hermes-wx .chat-main-content,
            html.hermes-wx .message-list-shell,
            html.hermes-wx .virtual-message-list,
            html.hermes-wx .virtual-message-list-content{background:#ededed!important}
            html.hermes-wx .chat-header{background:#f7f7f7!important;border-bottom:1px solid #dcdcdc!important;padding:10px 14px!important}
            html.hermes-wx .header-session-title{color:#111!important;font-size:16px!important;font-weight:600!important}
            html.hermes-wx .chat-input-area{background:#f7f7f7!important;border-top:1px solid #dcdcdc!important}
            html.hermes-wx .chat-input-area .input-wrapper{background:#ffffff!important;border:1px solid #e2e2e2!important;border-radius:8px!important;box-shadow:none!important}
            html.hermes-wx .chat-input-area .input-textarea,
            html.hermes-wx .chat-input-area textarea{background:transparent!important;color:#111!important;font-size:15px!important}
            html.hermes-wx .message{position:relative!important}
            html.hermes-wx .message.user{align-items:flex-end!important;padding-right:50px!important}
            html.hermes-wx .message.assistant{padding-left:50px!important}
            html.hermes-wx .message.user .msg-body{max-width:78%!important;position:static!important}
            html.hermes-wx .message.assistant .msg-body{max-width:82%!important}
            html.hermes-wx .message .message-author{position:absolute!important;top:0!important;margin:0!important;z-index:2!important}
            html.hermes-wx .message.user .message-author{right:0!important}
            html.hermes-wx .message.assistant .message-author{left:0!important}
            html.hermes-wx .message-author-name{display:none!important}
            html.hermes-wx .msg-avatar,
            html.hermes-wx .user-profile-avatar,
            html.hermes-wx .user-profile-avatar img,
            html.hermes-wx .user-profile-avatar svg{width:38px!important;height:38px!important;border-radius:6px!important;border:0!important;object-fit:cover!important}
            html.hermes-wx .message .message-bubble{border-radius:6px!important;padding:9px 12px!important;font-size:15px!important;line-height:1.55!important;box-shadow:none!important;border:0!important}
            html.hermes-wx .message.user .message-bubble:not(.system):not(.command):not(.agent-error){background:#95ec69!important;color:#111!important}
            html.hermes-wx .message.assistant .message-bubble:not(.system):not(.command):not(.agent-error){background:#ffffff!important;color:#111!important}
            html.hermes-wx .message.user .message-bubble:not(.system):not(.command):not(.agent-error) p,
            html.hermes-wx .message.user .message-bubble:not(.system):not(.command):not(.agent-error) li,
            html.hermes-wx .message.user .message-bubble:not(.system):not(.command):not(.agent-error) h1,
            html.hermes-wx .message.user .message-bubble:not(.system):not(.command):not(.agent-error) h2,
            html.hermes-wx .message.user .message-bubble:not(.system):not(.command):not(.agent-error) h3,
            html.hermes-wx .message.user .message-bubble:not(.system):not(.command):not(.agent-error) h4,
            html.hermes-wx .message.assistant .message-bubble:not(.system):not(.command):not(.agent-error) p,
            html.hermes-wx .message.assistant .message-bubble:not(.system):not(.command):not(.agent-error) li,
            html.hermes-wx .message.assistant .message-bubble:not(.system):not(.command):not(.agent-error) h1,
            html.hermes-wx .message.assistant .message-bubble:not(.system):not(.command):not(.agent-error) h2,
            html.hermes-wx .message.assistant .message-bubble:not(.system):not(.command):not(.agent-error) h3,
            html.hermes-wx .message.assistant .message-bubble:not(.system):not(.command):not(.agent-error) h4{color:#111!important}
            html.hermes-wx .message.user .message-bubble:not(.system):not(.command):not(.agent-error)::after{content:"";position:absolute;top:13px;right:-7px;width:0;height:0;border-top:6px solid transparent;border-bottom:6px solid transparent;border-left:7px solid #95ec69}
            html.hermes-wx .message.assistant .message-bubble:not(.system):not(.command):not(.agent-error)::after{content:"";position:absolute;top:13px;left:-7px;width:0;height:0;border-top:6px solid transparent;border-bottom:6px solid transparent;border-right:7px solid #ffffff}
            html.hermes-wx .message-time{color:#9a9a9a!important;font-size:11px!important}
            html.hermes-wx .message.user .message-meta{justify-content:flex-end!important}
            """;

    private ChatSkin() {
    }

    static String applyScript() {
        return "(function(){if(window.__hermesWx)return;window.__hermesWx=true;"
                + "var css=" + JSONObject.quote(CSS) + ";"
                + "function apply(){var d=document;if(!d.documentElement)return;"
                + "d.documentElement.classList.add('hermes-wx');"
                + "if(d.getElementById('hermes-wx-style'))return;"
                + "var s=d.createElement('style');s.id='hermes-wx-style';s.textContent=css;"
                + "(d.head||d.documentElement).appendChild(s);}"
                + "apply();"
                + "if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',apply);"
                + "})();";
    }

    static String removeScript() {
        return "(function(){var d=document;if(!d.documentElement)return;"
                + "d.documentElement.classList.remove('hermes-wx');"
                + "var s=d.getElementById('hermes-wx-style');if(s)s.remove();"
                + "window.__hermesWx=false;})();";
    }
}
