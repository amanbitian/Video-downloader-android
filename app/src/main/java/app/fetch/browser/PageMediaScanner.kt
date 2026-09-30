package app.fetch.browser

/**
 * One scoped, read-only DOM query run with evaluateJavascript — no JavaScript bridge is exposed to pages. It returns
 * what the page declares about its media (og:video, JSON-LD VideoObject, <video>/<audio> with size, position,
 * visibility and surroundings) so the primary video can be found before anything plays. Parsed by PageMediaSnapshot.
 */
object PageMediaScanner {
    const val MAX_ELEMENTS = 30

    val SCRIPT = """
        (() => {
          const abs = (u) => { try { return u ? new URL(u, location.href).href : ""; } catch (e) { return ""; } };
          const meta = (s) => { const m = document.querySelector(s); return m ? (m.getAttribute('content') || "") : ""; };
          const metas = (s) => Array.from(document.querySelectorAll(s)).map(m => abs(m.getAttribute('content'))).filter(Boolean);
          const ld = [];
          const visit = (node, depth) => {
            if (!node || depth > 6 || ld.length > 20) return;
            if (Array.isArray(node)) { node.forEach(n => visit(n, depth + 1)); return; }
            if (typeof node !== 'object') return;
            const type = [].concat(node['@type'] || []).join(' ');
            if (/VideoObject/i.test(type)) {
              const thumb = [].concat(node.thumbnailUrl || [])[0] || (node.thumbnail && node.thumbnail.url) || "";
              ld.push({ name: String(node.name || ""), contentUrl: abs(node.contentUrl), embedUrl: abs(node.embedUrl), thumbnailUrl: abs(thumb), duration: String(node.duration || "") });
            }
            ['@graph', 'video', 'mainEntity', 'mainEntityOfPage', 'associatedMedia', 'hasPart', 'itemListElement', 'item'].forEach(k => { if (node[k]) visit(node[k], depth + 1); });
          };
          document.querySelectorAll('script[type="application/ld+json"]').forEach(s => { try { visit(JSON.parse(s.textContent), 0); } catch (e) {} });
          const describe = (n) => [n.tagName, n.id, typeof n.className === 'string' ? n.className : '', n.getAttribute('role') || '',
            n.getAttribute('aria-label') || '', n.getAttribute('data-testid') || ''].join(' ').slice(0, 240);
          const keys = window.__fetchMediaKeys || (window.__fetchMediaKeys = new WeakMap());
          let next = window.__fetchMediaNext || 0;
          const videos = Array.from(document.querySelectorAll('video, audio')).slice(0, ${MAX_ELEMENTS}).map(el => {
            if (!keys.has(el)) keys.set(el, 'm' + (next++));
            const r = el.getBoundingClientRect();
            const cs = getComputedStyle(el);
            const sources = [el.currentSrc, el.getAttribute('src') ? el.src : ''].concat(Array.from(el.querySelectorAll('source')).map(s => s.src)).filter(Boolean);
            const context = [describe(el)];
            for (let n = el.parentElement, i = 0; n && i < 8; n = n.parentElement, i++) context.push(describe(n));
            return {
              key: keys.get(el), tag: el.tagName.toLowerCase(), sources: sources, poster: abs(el.getAttribute('poster')),
              x: r.left + window.scrollX, y: r.top + window.scrollY, w: r.width, h: r.height,
              visible: cs.display !== 'none' && cs.visibility !== 'hidden' && parseFloat(cs.opacity || '1') > 0.05 && r.width > 1 && r.height > 1,
              duration: isFinite(el.duration) ? el.duration : -1, muted: !!el.muted, autoplay: !!el.autoplay, loop: !!el.loop, context: context
            };
          });
          window.__fetchMediaNext = next;
          return JSON.stringify({
            url: location.href, title: document.title,
            ogTitle: meta('meta[property="og:title"]') || meta('meta[name="twitter:title"]'),
            site: meta('meta[property="og:site_name"]'),
            image: abs(meta('meta[property="og:image"]') || meta('meta[name="twitter:image"]')),
            ogVideos: metas('meta[property="og:video"], meta[property="og:video:url"], meta[property="og:video:secure_url"], meta[name="twitter:player:stream"]'),
            ld: ld, videos: videos, vw: window.innerWidth, vh: window.innerHeight, pw: document.documentElement.scrollWidth
          });
        })()
    """.trimIndent()
}
