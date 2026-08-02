package tech.kelma.app

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

internal data class CardHtmlFace(
    val body: String,
    val css: String,
    val images: List<CardMedia>,
    val audio: List<CardMedia> = emptyList(),
)

@Composable
internal fun RichOrFallbackCardFace(
    html: String?,
    css: String,
    text: String,
    audio: List<CardMedia>,
    images: List<CardMedia>,
    blocks: List<CardBlock>,
    textStyle: TextStyle,
    desktop: Boolean,
    onPlayAudio: (CardMedia) -> Unit,
) {
    if (desktop) DesktopCardFaceContent(text, audio, images, blocks, onPlayAudio)
    else CardFaceContent(text, audio, images, blocks, textStyle, onPlayAudio)
}

internal expect fun shouldUseRichReviewCard(): Boolean

@Composable
internal expect fun PlatformHtmlCardFace(
    html: String,
    updateScript: String,
    onPlayAudio: (String) -> Unit,
    onCardTap: (Float) -> Unit = {},
    modifier: Modifier = Modifier,
)

@OptIn(ExperimentalEncodingApi::class)
internal fun buildCardHtmlDocument(
    face: CardHtmlFace,
    desktop: Boolean,
    scrollToAnswer: Boolean = false,
): String {
    val media = face.images.associateBy { normalizeRichMediaName(it.filename) }
    val withImages = RichImageTag.replace(face.body) { match ->
        val source = normalizeRichMediaName(match.groupValues[2])
        val image = media[source]
        if (image == null) {
            "<span class=\"kelma-missing-media\">Missing image: ${escapeHtml(source)}</span>"
        } else {
            val encoded = Base64.Default.encode(image.bytes)
            val dataUrl = "data:${imageMimeType(source)};base64,$encoded"
            match.groupValues[1] + dataUrl + match.groupValues[3]
        }
    }
    val audioNames = face.audio.map { normalizeRichMediaName(it.filename) }.toSet()
    val body = RichSoundToken.replace(withImages) { match ->
        val filename = normalizeRichMediaName(match.groupValues[1])
        if (filename !in audioNames) {
            "<span class=\"kelma-missing-media\">Missing audio: ${escapeHtml(filename)}</span>"
        } else {
            "<a href=\"kelma-audio:${encodeUriComponent(filename)}\" class=\"kelma-audio\" " +
                "data-kelma-audio=\"${escapeHtml(filename)}\" aria-label=\"Play ${escapeHtml(filename)}\">" +
                "<span class=\"kelma-audio-play\" aria-hidden=\"true\"></span></a>"
        }
    }
    val foreground = if (desktop) "#F4F1E7" else "#F4F1E7"
    val bodyLayout = if (desktop) {
        "display:grid;grid-template-rows:minmax(24px,1fr) auto minmax(24px,3fr)"
    } else {
        "display:flex;flex-direction:column"
    }
    val contentLayout = if (desktop) "grid-row:2;margin-block:0" else "margin-block:auto"
    val imageBounds = if (desktop) {
        "max-width:min(62%,520px)!important;max-height:42vh!important"
    } else {
        "max-width:100%!important"
    }
    val answerScrollScript = if (scrollToAnswer) {
        "window.addEventListener('load',function(){requestAnimationFrame(function(){var a=document.getElementById('answer');if(a)a.scrollIntoView({block:'start',inline:'nearest'});});});"
    } else {
        ""
    }
    return """<!doctype html>
<html>
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1,maximum-scale=5">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src data:; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
<style id="kelma-card-style">
html,body{margin:0;padding:0;min-height:100%;background:transparent;color:$foreground;font-family:-apple-system,BlinkMacSystemFont,"Segoe UI",sans-serif;overflow-wrap:anywhere}
body{box-sizing:border-box;min-height:100vh;font-size:${if (desktop) 20 else 22}px;line-height:1.5;text-align:center;$bodyLayout}
#kelma-card-content{box-sizing:border-box;width:100%;$contentLayout}
pre{white-space:pre-wrap;text-align:left} blockquote{margin-left:1em;border-left:3px solid #C9AC6B;padding-left:.8em}
a{color:#DCC48F} $RichCardAccessibilityCss
${face.css}
html,body.card{background:transparent!important} body.card{color:$foreground!important;$bodyLayout}
#kelma-card-content{$contentLayout}
html,body{max-width:100%!important;overflow-x:hidden!important;overflow-y:auto!important}
img{$imageBounds;height:auto!important;object-fit:contain!important} table{max-width:100%;border-collapse:collapse}
.kelma-missing-media{display:inline-block!important;padding:6px 9px!important;border:1px solid #FF6B73!important;border-radius:6px!important;color:#FFB0B5!important;font:13px/1.3 sans-serif!important}
a.kelma-audio{all:initial!important;box-sizing:border-box!important;display:inline-flex!important;align-items:center!important;justify-content:center!important;width:38px!important;height:38px!important;margin:2px 4px!important;border-radius:50%!important;border:1px solid #DCC48F!important;background:#C9AC6B!important;vertical-align:middle!important;cursor:pointer!important}
a.kelma-audio .kelma-audio-play{all:initial!important;display:block!important;box-sizing:border-box!important;width:0!important;height:0!important;margin-left:3px!important;border-top:7px solid transparent!important;border-bottom:7px solid transparent!important;border-left:11px solid #0F100A!important}
</style>
</head>
<body class="card" style="background-color:transparent!important;color:$foreground!important"><!--kelma-card-body-start--><main id="kelma-card-content">$body</main><!--kelma-card-body-end-->
<script id="kelma-host-script">$answerScrollScript (function(){if(window.__kelmaTapBound)return;window.__kelmaTapBound=1;var SLOP=12,LONG=600,sx=0,sy=0,st=0,moved=false,active=false,handledAt=0;function playAudio(n){if(window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers.kelmaAudio){window.webkit.messageHandlers.kelmaAudio.postMessage(n);}else if(window.kelmaHost){window.kelmaHost.playAudio(n);}else{window.location.href='kelma-audio:'+encodeURIComponent(n);}}function cardTap(x){if(window.webkit&&window.webkit.messageHandlers&&window.webkit.messageHandlers.kelmaCardTap){window.webkit.messageHandlers.kelmaCardTap.postMessage(x);}else if(window.kelmaHost){window.kelmaHost.cardTap(x);}else{window.location.href='kelma-tap:'+x;}}function pointerCursor(t){for(var i=0;t&&t.nodeType===1&&i<8;i++){if(window.getComputedStyle&&getComputedStyle(t).cursor==='pointer')return true;t=t.parentElement;}return false;}function interactive(t){if(!t||!t.closest)return false;if(t.closest('a,button,input,textarea,select,label,summary,details,video,audio,iframe,object,embed,[contenteditable=true],[role=button],[role=link],[onclick],[tabindex]'))return true;return pointerCursor(t);}function resolve(t,cx){var b=t&&t.closest?t.closest('[data-kelma-audio]'):null;if(b){playAudio(b.getAttribute('data-kelma-audio'));return true;}if(interactive(t))return false;cardTap(Math.max(0,Math.min(1,cx/Math.max(1,window.innerWidth))));return true;}document.addEventListener('touchstart',function(e){if(e.touches.length!==1){active=false;return;}var t=e.touches[0];sx=t.clientX;sy=t.clientY;st=Date.now();moved=false;active=true;},{passive:true});document.addEventListener('touchmove',function(e){if(!active)return;var t=e.touches[0];if(Math.abs(t.clientX-sx)>SLOP||Math.abs(t.clientY-sy)>SLOP)moved=true;},{passive:true});document.addEventListener('touchend',function(e){if(!active)return;active=false;var endedAt=Date.now();if(moved||endedAt-st>LONG)return;if(resolve(e.target,sx)){handledAt=endedAt;e.preventDefault();}},{passive:false});document.addEventListener('click',function(e){var a=e.target&&e.target.closest?e.target.closest('a'):null;var b=e.target&&e.target.closest?e.target.closest('[data-kelma-audio]'):null;if(!b&&interactive(e.target)){if(a)e.preventDefault();return;}if(Date.now()-handledAt<700){e.preventDefault();return;}if(a)e.preventDefault();resolve(e.target,e.clientX);});})();</script>
</body>
</html>"""
}

@OptIn(ExperimentalEncodingApi::class)
internal fun cardDomUpdateScript(html: String): String {
    val css = html.substringAfter(CardStyleMarker, "").substringBefore("</style>")
    val body = html.substringAfter(CardBodyStartMarker, "").substringBefore(CardBodyEndMarker)
    val encodedCss = Base64.Default.encode(css.encodeToByteArray())
    val encodedBody = Base64.Default.encode(body.encodeToByteArray())
    val scrollToAnswer = html.contains("getElementById('answer')")
    return """
        (function(){
          var decode=function(value){var bytes=Uint8Array.from(atob(value),function(c){return c.charCodeAt(0);});return new TextDecoder('utf-8').decode(bytes);};
          document.getElementById('kelma-card-style').textContent=decode('$encodedCss');
          document.body.className='card';
          document.body.setAttribute('style','margin:0;background:transparent!important;color:#F4F1E7!important');
          document.body.innerHTML=decode('$encodedBody');
          Array.from(document.body.querySelectorAll('script')).forEach(function(oldScript){
            var script=document.createElement('script');
            Array.from(oldScript.attributes).forEach(function(attribute){script.setAttribute(attribute.name,attribute.value);});
            script.text=oldScript.textContent;
            oldScript.replaceWith(script);
          });
          document.dispatchEvent(new Event('DOMContentLoaded'));
          var settle=function(){
            window.dispatchEvent(new Event('load'));
            if($scrollToAnswer){requestAnimationFrame(function(){var answer=document.getElementById('answer');if(answer)answer.scrollIntoView({block:'start',inline:'nearest'});});}else{window.scrollTo(0,0);}
          };
          var pending=Array.from(document.images).filter(function(image){return !image.complete;});
          if(pending.length===0){settle();}else{Promise.all(pending.map(function(image){return new Promise(function(done){image.addEventListener('load',done,{once:true});image.addEventListener('error',done,{once:true});});})).then(settle);}
        })();
    """.trimIndent()
}

internal const val CardStyleMarker = "<style id=\"kelma-card-style\">"
internal const val CardBodyStartMarker = "<!--kelma-card-body-start-->"
internal const val CardBodyEndMarker = "<!--kelma-card-body-end-->"

private val RichImageTag = Regex(
    """(<img\b[^>]*\bsrc\s*=\s*["'])([^"']+)(["'][^>]*>)""",
    RegexOption.IGNORE_CASE,
)
private val RichSoundToken = Regex("""\[sound:([^]]+)]""", RegexOption.IGNORE_CASE)
private val RichCardAccessibilityCss = "@media(prefers-reduced-motion:reduce){*,*:before,*:after{animation:none!important;transition:none!important}}"

private fun normalizeRichMediaName(value: String): String = value
    .substringAfterLast('/')
    .replace("%20", " ")
    .replace("&amp;", "&")

private fun imageMimeType(filename: String): String = when (filename.substringAfterLast('.', "").lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "gif" -> "image/gif"
    "webp" -> "image/webp"
    "bmp" -> "image/bmp"
    "svg" -> "image/svg+xml"
    else -> "image/png"
}

private fun encodeUriComponent(value: String): String = buildString {
    value.encodeToByteArray().forEach { byte ->
        val unsigned = byte.toInt() and 0xff
        if (unsigned in 'a'.code..'z'.code || unsigned in 'A'.code..'Z'.code ||
            unsigned in '0'.code..'9'.code || unsigned in listOf('-', '_', '.', '~').map(Char::code)
        ) {
            append(unsigned.toChar())
        } else {
            append('%')
            append(unsigned.toString(16).uppercase().padStart(2, '0'))
        }
    }
}

internal fun escapeHtml(value: String): String = value
    .replace("&", "&amp;")
    .replace("<", "&lt;")
    .replace(">", "&gt;")
    .replace("\"", "&quot;")
