/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */

/**
 * SPA (Single Page Application) navigation for the left panel document tree.
 * Intercepts clicks on tree nodes in #leftPanels and loads the target page
 * content via AJAX, replacing only the #contentcolumn area while keeping the
 * navigation tree (and its open/close state) intact.
 *
 * Manual page refresh (F5 / browser refresh) triggers a normal full page load.
 */
(function() {
  'use strict';

  // Only activate on pages that have a navigation tree in the left panel.
  var treeContainer = document.querySelector('#leftPanels .xtree');
  if (!treeContainer) {
    return;
  }

  // ---------------------------------------------------------------------------
  // jQuery reference helper
  // In XWiki Flamingo, the global $ may be Prototype.js, not jQuery.
  // We access jQuery via window.jQuery (the noConflict global) or RequireJS.
  // ---------------------------------------------------------------------------

  /**
   * Try to get a jQuery reference. Since our script loads after jQuery,
   * window.jQuery should be available. Falls back to null if not.
   */
  function getJQuery() {
    // Use window.jQuery which is always jQuery even after noConflict.
    if (window.jQuery && window.jQuery.fn && window.jQuery.fn.jquery) {
      return window.jQuery;
    }
    return null;
  }

  // ---------------------------------------------------------------------------
  // State tracking
  // ---------------------------------------------------------------------------

  var pendingRequest = null;
  var isNavigating = false;

  /**
   * Save the current tree state to sessionStorage as a safety net.
   * Called before SPA navigation so that if a popstate triggers a full
   * page reload, we can attempt to restore the tree state.
   */
  function saveTreeState($) {
    try {
      if (!$ || !$.jstree) return;
      var tree = $.jstree.reference(treeContainer);
      if (tree) {
        var state = tree.get_state();
        sessionStorage.setItem('xwiki-tree-spa-state', JSON.stringify(state));
        var leftPanels = document.getElementById('leftPanels');
        if (leftPanels) {
          sessionStorage.setItem('xwiki-tree-spa-scroll', leftPanels.scrollTop);
        }
      }
    } catch (e) {
      // sessionStorage might be unavailable; ignore.
    }
  }

  /**
   * Restore tree state from sessionStorage on full page load (safety net).
   */
  function restoreTreeState() {
    try {
      var stateJson = sessionStorage.getItem('xwiki-tree-spa-state');
      if (stateJson) {
        var state = JSON.parse(stateJson);
        require(['jquery', 'xwiki-tree'], function($) {
          if (!$.jstree) return;
          var tree = $.jstree.reference(treeContainer);
          if (tree) {
            $(treeContainer).one('ready.jstree', function() {
              tree.restore_state(state);
              var scrollTop = sessionStorage.getItem('xwiki-tree-spa-scroll');
              var leftPanels = document.getElementById('leftPanels');
              if (scrollTop && leftPanels) {
                leftPanels.scrollTop = parseInt(scrollTop, 10);
              }
            });
          }
        });
      }
      // Clean up so a manual refresh doesn't restore stale state.
      sessionStorage.removeItem('xwiki-tree-spa-state');
      sessionStorage.removeItem('xwiki-tree-spa-scroll');
    } catch (e) {
      // Ignore.
    }
  }

  /**
   * Normalize a URL for comparison: strip trailing slash, keep origin+path+search.
   */
  function normalizeUrl(url) {
    try {
      var u = new URL(url, window.location.origin);
      return u.origin + u.pathname.replace(/\/+$/, '') + u.search;
    } catch (e) {
      return url;
    }
  }

  /**
   * Check if two URLs point to the same page.
   */
  function isSamePage(url1, url2) {
    return normalizeUrl(url1) === normalizeUrl(url2);
  }

  // ---------------------------------------------------------------------------
  // Utility: Extract the space from an XWiki view/get URL
  // URL format: /xwiki/bin/view/SpaceName/PageName
  //             /xwiki/bin/view/Parent/Child/PageName (nested spaces)
  // Returns the space path, or null if the URL doesn't match the pattern.
  // ---------------------------------------------------------------------------

  function getSpaceFromUrl(url) {
    try {
      var urlObj = new URL(url, window.location.origin);
      // Strip trailing slashes: /xwiki/bin/view/Main/ → /xwiki/bin/view/Main
      var path = urlObj.pathname.replace(/\/+$/, '');
      // Match /view/SpacePath/PageName or /get/SpacePath/PageName
      var match = path.match(/\/(view|get)\/(.+)\/([^/]+)$/);
      if (match) {
        return match[2]; // The space path, e.g. "DevTeam" or "Parent/Child"
      }
      // Handle WebHome shorthand: /view/SpacePath (no page name)
      match = path.match(/\/(view|get)\/(.+)$/);
      if (match) {
        return match[2]; // The space path, page is implicit WebHome
      }
    } catch (e) {
      // Ignore parse errors.
    }
    return null;
  }

  /**
   * Get the current page's space from the URL.
   */
  function getCurrentSpace() {
    return getSpaceFromUrl(window.location.href);
  }

  /**
   * Check if two space paths refer to the same space.
   * Handles trailing slashes and encoding differences.
   */
  function isSameSpace(space1, space2) {
    if (!space1 || !space2) {
      // If we can't determine the space for either URL, be safe and return false
      // (will trigger full page reload).
      return false;
    }
    // Normalize: trim trailing slashes and decode.
    var normalize = function(s) {
      return decodeURIComponent(s).replace(/\/+$/, '').replace(/^\/+/, '');
    };
    return normalize(space1) === normalize(space2);
  }

  // ---------------------------------------------------------------------------
  // Utility: Check if a URL is safe for SPA navigation
  // ---------------------------------------------------------------------------

  function isSPACompatible(url) {
    var currentOrigin = window.location.origin;
    try {
      var urlObj = new URL(url, currentOrigin);
      if (urlObj.origin !== currentOrigin) {
        return false;
      }
    } catch (e) {
      return false;
    }
    // Skip URLs that navigate to edit, admin, or other non-view actions.
    if (/\/(edit|admin|create|copy|delete|export|import|register|login|logout)\//.test(url)) {
      return false;
    }
    // Cross-space navigation: when moving to a different space, the left
    // navigation tree must be refreshed (different root, children, exclusions).
    // Do a full page reload for cross-space navigation.
    var targetSpace = getSpaceFromUrl(url);
    if (!isSameSpace(getCurrentSpace(), targetSpace)) {
      return false;
    }
    return true;
  }

  /**
   * Check if a tree node is a pagination node by looking at the anchor's
   * parent li element for pagination-specific data.
   * This avoids requiring jsTree's API to be available at click time.
   */
  function isPaginationNode(anchor) {
    // Check the li element for pagination type markers.
    var li = anchor.closest('li');
    if (!li) return false;
    // jsTree pagination nodes have a specific anchor class or role.
    if (anchor.classList.contains('jstree-pagination')) return true;
    // Also try the jsTree API if available.
    var $jq = getJQuery();
    if ($jq && $jq.jstree) {
      try {
        var tree = $jq.jstree.reference(treeContainer);
        if (tree) {
          var node = tree.get_node(li);
          if (node && node.data && node.data.type === 'pagination') {
            return true;
          }
        }
      } catch (e) {
        // Ignore.
      }
    }
    return false;
  }

  // ---------------------------------------------------------------------------
  // Content processing
  // ---------------------------------------------------------------------------

  function processContent(url, html, $) {
    var parser = new DOMParser();
    var responseDoc = parser.parseFromString(html, 'text/html');

    var newContentColumn = responseDoc.getElementById('contentcolumn');
    if (!newContentColumn) {
      window.location.href = url;
      return;
    }

    // 1. Update document title.
    if (responseDoc.title) {
      document.title = responseDoc.title;
    }

    // 2. Update <html> data-xwiki-* attributes.
    var responseHtml = responseDoc.documentElement;
    var currentHtml = document.documentElement;
    if (responseHtml && currentHtml) {
      var attrsToRemove = [];
      for (var i = 0; i < currentHtml.attributes.length; i++) {
        if (currentHtml.attributes[i].name.indexOf('data-xwiki-') === 0) {
          attrsToRemove.push(currentHtml.attributes[i].name);
        }
      }
      attrsToRemove.forEach(function(attr) {
        currentHtml.removeAttribute(attr);
      });
      for (var j = 0; j < responseHtml.attributes.length; j++) {
        var attr = responseHtml.attributes[j];
        if (attr.name.indexOf('data-xwiki-') === 0) {
          currentHtml.setAttribute(attr.name, attr.value);
        }
      }
    }

    // 3. Update form token.
    var responseFormToken = responseDoc.querySelector('meta[name=form_token]');
    if (responseFormToken) {
      var currentFormToken = document.querySelector('meta[name=form_token]');
      if (currentFormToken) {
        currentFormToken.setAttribute('content', responseFormToken.getAttribute('content'));
      }
    }

    // 4. Update XWiki globals.
    updateXWikiGlobals($);

    // 5. Sync contentcontainer CSS classes (panel visibility).
    var responseContainer = responseDoc.getElementById('contentcontainer');
    var currentContainer = document.getElementById('contentcontainer');
    if (responseContainer && currentContainer) {
      currentContainer.className = responseContainer.className;
    }

    // 6. Handle skin extension imports from the new content.
    var newContentColumnEl = $(newContentColumn);
    newContentColumnEl.find('noscript.skin-extension-imports').each(function() {
      var imports = $('head').find('link, script').map(function() {
        return $(this).attr('href') || $(this).attr('src');
      }).get();
      $(document.createElement('div')).append($(this).text())
        .find('link, script').each(function() {
          var itemUrl = $(this).attr('href') || $(this).attr('src');
          if (itemUrl && imports.indexOf(itemUrl) < 0) {
            $('head').append(this);
          }
        });
    });

    // 7. Replace #contentcolumn content.
    var currentContentColumn = document.getElementById('contentcolumn');
    currentContentColumn.innerHTML = newContentColumn.innerHTML;

    // 8. Update browser URL via History API.
    if (window.location.href !== url) {
      window.history.pushState({ url: url }, document.title, url);
    }

    // 9. Update tree data-openTo for full page reload correctness.
    var referenceAttr = currentHtml.getAttribute('data-xwiki-reference');
    if (referenceAttr && treeContainer) {
      treeContainer.setAttribute('data-opento', 'document:' + referenceAttr);
    }

    // 10. Update tree selection to highlight the current page.
    updateTreeSelection($);

    // 11. Fire xwiki:dom:updated scoped to contentcolumn only.
    $(document).trigger('xwiki:dom:updated', {
      'elements': [currentContentColumn]
    });

    // 12. Scroll to top.
    window.scrollTo(0, 0);
  }

  function updateXWikiGlobals($) {
    var htmlEl = document.documentElement;
    var docReference = htmlEl.getAttribute('data-xwiki-reference');
    var wiki = htmlEl.getAttribute('data-xwiki-wiki');
    var space = htmlEl.getAttribute('data-xwiki-space');
    var page = htmlEl.getAttribute('data-xwiki-page');

    if (wiki && space && page) {
      if (typeof XWiki !== 'undefined') {
        XWiki.currentWiki = wiki;
        XWiki.currentSpace = space;
        XWiki.currentPage = page;
      }
    }

    if (docReference) {
      try {
        require(['xwiki-meta'], function(xwikiMeta) {
          if (typeof xwikiMeta.init === 'function') {
            xwikiMeta.init();
          }
        });
      } catch (e) {
        // Ignore if xwiki-meta is not available.
      }
    }

    if (wiki && space && page) {
      var contextPath = (typeof XWiki !== 'undefined' && XWiki.contextPath) || '';
      var servletPath = (typeof XWiki !== 'undefined' && XWiki.servletpath) || 'bin';
      window.docviewurl = contextPath + '/' + servletPath + '/view/' + space + '/' + page;
      window.docgeturl = contextPath + '/' + servletPath + '/get/' + space + '/' + page;
      window.docediturl = contextPath + '/' + servletPath + '/edit/' + space + '/' + page;
    }
  }

  // ---------------------------------------------------------------------------
  // Tree selection update
  // ---------------------------------------------------------------------------

  /**
   * Update the jsTree selection to highlight the node that corresponds to the
   * current page. This is called after a successful SPA navigation so the tree
   * visually reflects which page is active.
   */
  function updateTreeSelection($) {
    if (!$ || !$.jstree) return;
    var tree = $.jstree.reference(treeContainer);
    if (!tree) return;

    var ref = document.documentElement.getAttribute('data-xwiki-reference');
    if (!ref) return;

    var nodeId = 'document:' + ref;

    // If the node is already loaded in the tree, select it directly.
    if (tree.get_node(nodeId)) {
      tree.deselect_all();
      tree.select_node(nodeId);
    } else {
      // Node not yet loaded (parent not expanded). Use openTo to expand
      // the path and select it. This triggers an AJAX call to get the path
      // but since we're in the same space, it's usually fast.
      tree.openTo(nodeId);
    }
  }

  // ---------------------------------------------------------------------------
  // AJAX navigation
  // ---------------------------------------------------------------------------

  function spaNavigate(url) {
    if (isNavigating) {
      return;
    }

    if (pendingRequest) {
      pendingRequest.abort();
      pendingRequest = null;
    }

    isNavigating = true;

    require(['jquery'], function($) {
      saveTreeState($);

      pendingRequest = $.ajax({
        url: url,
        method: 'GET',
        dataType: 'html',
        headers: {
          'X-XWiki-SPA-Navigation': 'true'
        },
        timeout: 30000
      });

      pendingRequest
        .done(function(html) {
          pendingRequest = null;
          processContent(url, html, $);
        })
        .fail(function() {
          pendingRequest = null;
          window.location.href = url;
        })
        .always(function() {
          isNavigating = false;
        });
    });
  }

  // ---------------------------------------------------------------------------
  // Capture-phase click interception
  // ---------------------------------------------------------------------------

  document.addEventListener('click', function(event) {
    // Only intercept clicks on tree anchor elements.
    var anchor = event.target.closest('a.jstree-anchor');
    if (!anchor) {
      return;
    }

    // Only intercept clicks inside the left-panel navigation tree.
    if (!anchor.closest('#leftPanels .xtree')) {
      return;
    }

    // Allow modifier-key clicks (new tab / new window).
    if (event.ctrlKey || event.metaKey || event.shiftKey) {
      return;
    }

    // Allow middle-click (open in new tab).
    if (event.button && event.button !== 0) {
      return;
    }

    // Get the target URL.
    var url = anchor.href;
    if (!url || isSamePage(url, window.location.href)) {
      return;
    }

    // Don't intercept pagination nodes.
    if (isPaginationNode(anchor)) {
      return;
    }

    // Skip non-SPA-compatible URLs.
    if (!isSPACompatible(url)) {
      return;
    }

    // Intercept: prevent full page navigation.
    event.preventDefault();
    event.stopPropagation();

    spaNavigate(url);
  }, true);

  // ---------------------------------------------------------------------------
  // Browser back/forward handling
  // ---------------------------------------------------------------------------

  window.addEventListener('popstate', function(event) {
    if (pendingRequest) {
      pendingRequest.abort();
      pendingRequest = null;
    }

    if (event.state && event.state.url) {
      // If the popstate URL is in a different space, do a full page reload
      // so the navigation tree is correctly refreshed.
      if (!isSPACompatible(event.state.url)) {
        window.location.href = event.state.url;
        return;
      }
      spaNavigate(event.state.url);
    }
  });

  // ---------------------------------------------------------------------------
  // Safety net: try to restore tree state on full page load after popstate
  // ---------------------------------------------------------------------------

  if (window.history.state && window.history.state.url) {
    restoreTreeState();
  }

})();
