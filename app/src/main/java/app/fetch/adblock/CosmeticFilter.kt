package app.fetch.adblock

/**
 * Element hiding for pages where ad requests were blocked: collapses the space the page reserved for ads (an
 * "Advertisement" label over a blank box). Injected with evaluateJavascript; it exposes nothing to the page.
 *
 * Deliberately conservative — an element is hidden only when it is
 *  - a well-known ad container (AdSense, Google Publisher Tag, ad iframes), or
 *  - named like an ad slot (class/id word "ad", "ads", "advert", "gpt", "dfp", "sponsored"…) AND holds nothing but an
 *    ad label: no other text and no visible image, video, picture or canvas.
 * Real content never matches the second rule, so a false positive can only hide an already-empty box.
 * A debounced MutationObserver repeats the pass for ads inserted while scrolling.
 */
object CosmeticFilter {
    val SCRIPT = """
        (() => {
          if (window.__fetchAdHide) { window.__fetchAdHide(); return; }
          const KNOWN = 'ins.adsbygoogle, .adsbygoogle, [id^="div-gpt-ad"], [id^="google_ads_iframe"], [id^="gpt-ad"], ' +
            'iframe[src*="doubleclick.net"], iframe[src*="googlesyndication.com"], iframe[id^="google_ads"], [data-google-query-id]';
          const style = document.createElement('style');
          style.textContent = KNOWN + ' { display: none !important; }';
          (document.head || document.documentElement).appendChild(style);

          const AD_WORD = /(^|\s)(ad|ads|advert|adverts|advertisement|adslot|adunit|adbox|adblock|adcontainer|adwrapper|adsense|gpt|dfp|sponsor|sponsored|banner ad)(\s|$)/i;
          const LABEL = /^(advertisement|advertisment|advertising|ad|ads|sponsored|promoted|story continues below advertisement)$/i;
          const words = (el) => ((el.id || '') + ' ' + (typeof el.className === 'string' ? el.className : ''))
            .replace(/([a-z0-9])([A-Z])/g, '$1 $2').replace(/[^A-Za-z0-9]+/g, ' ');
          const onlyLabel = (el) => { const t = (el.innerText || '').trim(); return t === '' || LABEL.test(t); };
          const hasMedia = (el) => Array.from(el.querySelectorAll('img, video, picture, canvas')).some(m => {
            const r = m.getBoundingClientRect(); return r.width > 40 && r.height > 40;
          });
          const gone = (el) => el.__fetchHidden || getComputedStyle(el).display === 'none';
          // Hide, then collapse wrappers the ad leaves empty (e.g. <div class="minHeight325"> reserving the slot's height).
          const collapse = (el) => {
            el.style.setProperty('display', 'none', 'important'); el.__fetchHidden = true;
            for (let p = el.parentElement, i = 0; p && i < 4 && p !== document.body; p = p.parentElement, i++) {
              if (p.__fetchHidden || !Array.from(p.children).every(gone) || !onlyLabel(p) || hasMedia(p)) break;
              p.style.setProperty('display', 'none', 'important'); p.__fetchHidden = true;
            }
          };

          const pass = () => {
            // 1. Slots named like ads that hold nothing but a label.
            document.querySelectorAll('[class*="ad" i], [id*="ad" i], [class*="sponsor" i], [class*="gpt" i], [class*="dfp" i]').forEach(el => {
              if (el.__fetchHidden || el === document.body || el === document.documentElement) return;
              if (!AD_WORD.test(words(el))) return;
              if (onlyLabel(el) && !hasMedia(el)) collapse(el);
            });
            // 2. Wrappers of known ad containers that are otherwise just a label ("Advertisement" above a GPT slot).
            document.querySelectorAll(KNOWN).forEach(slot => {
              let node = slot.parentElement;
              for (let i = 0; node && i < 3 && node !== document.body; i++, node = node.parentElement) {
                if (onlyLabel(node) && !hasMedia(node)) collapse(node); else break;
              }
            });
          };
          let timer = 0;
          window.__fetchAdHide = () => { clearTimeout(timer); timer = setTimeout(pass, 250); };
          pass();
          new MutationObserver(() => window.__fetchAdHide()).observe(document.documentElement, { childList: true, subtree: true });
        })()
    """.trimIndent()
}
