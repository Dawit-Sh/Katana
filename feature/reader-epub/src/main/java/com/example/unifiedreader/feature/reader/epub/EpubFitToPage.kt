package com.example.unifiedreader.feature.reader.epub

import org.readium.r2.navigator.epub.EpubNavigatorFragment
import org.readium.r2.shared.publication.Publication
import org.readium.r2.shared.publication.epub.EpubLayout
import org.readium.r2.shared.publication.presentation.presentation

/**
 * Keeps every line of a reflowable page inside the page, whatever the book's own styles ask for.
 *
 * - A first line in capitals (`p.opener::first-line { text-transform: uppercase }`, common in chapter
 *   openers): the Fire's WebView 98 picks where that line breaks from the lowercase letters and only then
 *   draws them as capitals, so the line runs past the right edge (The Hole, 2026-11-27). Such rules get
 *   the font's own small capitals instead, a little bigger so they look the same; the WebView measures
 *   those right. A first line in capitalized words drops the capitals for the same reason.
 * - A long word or link with nowhere to break wraps.
 * - A table the book made wider than the page (`width: 800px`) takes the page's width, its words wrapping.
 * - A run of words the book asked never to break (`white-space: nowrap`) that is wider than the page
 *   breaks like any other text.
 * - A picture on its own in an indented paragraph loses the indent, which pushed it past the right edge.
 *
 * The last three are checked again on every run, since a bigger text size can make one too wide.
 *
 * Safe to run again on every page load and page turn: the rules are rewritten once per chapter.
 */
internal val EpubFitToPageScript = """
    (function() {
      var doc = document;
      var body = doc.body;
      if (!body) return 'missing-body';
      if (!doc.getElementById('katana-fit')) {
        var style = doc.createElement('style');
        style.id = 'katana-fit';
        style.textContent = 'body{overflow-wrap:break-word!important}td,th{overflow-wrap:anywhere!important}';
        (doc.head || doc.documentElement).appendChild(style);
      }
      var capitals = 0;
      if (!window.__katanaFit) {
        var nowrap = ['[style*="nowrap"]'];
        var bigger = function(size) {
          if (!size) return '$EpubSmallCapsPercent%';
          var m = /^([0-9.]+)(%|em|rem)$/.exec(size);
          return m ? (parseFloat(m[1]) * $EpubSmallCapsScale).toFixed(3) + m[2] : size;
        };
        var visit = function(rules) {
          for (var i = 0; rules && i < rules.length; i++) {
            var rule = rules[i];
            try {
              if (rule.styleSheet) visit(rule.styleSheet.cssRules);
              if (rule.cssRules) visit(rule.cssRules);
            } catch (e) {}
            var css = rule.style;
            var selector = rule.selectorText;
            if (!css || !selector) continue;
            var transform = css.textTransform;
            if (/:first-line/i.test(selector) && transform && transform !== 'none' && transform !== 'lowercase') {
              css.setProperty('text-transform', 'none', 'important');
              if (transform === 'uppercase') {
                css.setProperty('font-variant-caps', 'all-small-caps', 'important');
                css.setProperty('font-size', bigger(css.fontSize), css.getPropertyPriority('font-size'));
              }
              capitals++;
            }
            if (css.whiteSpace === 'nowrap' && selector.indexOf('::') < 0 && !/:first-(line|letter)/i.test(selector)) {
              nowrap.push(selector);
            }
          }
        };
        for (var s = 0; s < doc.styleSheets.length; s++) {
          try { visit(doc.styleSheets[s].cssRules); } catch (e) {}
        }
        window.__katanaFit = nowrap;
      }
      var padding = window.getComputedStyle(body);
      var width = body.clientWidth - parseFloat(padding.paddingLeft || 0) - parseFloat(padding.paddingRight || 0);
      var unwrapped = 0;
      var tables = 0;
      var pictures = 0;
      var tooWide = function(el) { return el.getBoundingClientRect().width > width + 1; };
      if (width > 0) {
        window.__katanaFit.forEach(function(selector) {
          var found;
          try { found = doc.querySelectorAll(selector); } catch (e) { return; }
          for (var j = 0; j < found.length; j++) {
            if (tooWide(found[j])) {
              found[j].style.setProperty('white-space', 'normal', 'important');
              unwrapped++;
            }
          }
        });
        var table = doc.getElementsByTagName('table');
        for (var t = 0; t < table.length; t++) {
          if (tooWide(table[t])) {
            table[t].style.setProperty('width', '100%', 'important');
            table[t].style.setProperty('min-width', '0', 'important');
            table[t].style.setProperty('table-layout', 'auto', 'important');
            tables++;
          }
        }
        var media = doc.querySelectorAll('img, svg, video');
        for (var k = 0; k < media.length; k++) {
          var holder = media[k].parentElement;
          if (holder && holder !== body && !holder.textContent.trim() &&
              media[k].getBoundingClientRect().width > width * 0.8 &&
              parseFloat(window.getComputedStyle(holder).textIndent) !== 0) {
            holder.style.setProperty('text-indent', '0', 'important');
            pictures++;
          }
        }
      }
      return 'fit:' + capitals + ':' + unwrapped + ':' + tables + ':' + pictures;
    })();
""".trimIndent()

/** Runs [EpubFitToPageScript] on the page the navigator shows; fixed-layout books are left as they are. */
internal suspend fun applyEpubFitToPage(navigator: EpubNavigatorFragment, publication: Publication) {
    if (publication.metadata.presentation.layout == EpubLayout.FIXED) return
    navigator.evaluateJavascript(EpubFitToPageScript)
}

// Small capitals stand about as tall as lowercase letters; this much bigger, a first line in them looks
// like the book's capitals at 85%, the usual size for them (checked on the Fire with Literata).
private const val EpubSmallCapsScale = 1.18
private const val EpubSmallCapsPercent = 118
