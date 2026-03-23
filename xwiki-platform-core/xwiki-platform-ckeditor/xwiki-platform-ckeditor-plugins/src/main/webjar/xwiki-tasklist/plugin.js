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
/* global CKEDITOR, setTimeout */
/* jshint maxstatements: 60 */
(function() {
  'use strict';

  var TASK_LIST_CLASS = 'task-list';
  var TASK_ITEM_CLASS = 'task-list-item';
  var TASK_ITEM_CHECKED_CLASS = 'task-list-item-checked';
  var TASK_TOGGLE_CLASS = 'xwiki-task-list-toggle';
  var UNCHECKED_MARKER = '[ ] ';
  var CHECKED_MARKER = '[x] ';
  var CLICK_TOGGLE_OFFSET = 24;

  CKEDITOR.plugins.add('xwiki-tasklist', {
    requires: 'list,xwiki-localization',

    init: function(editor) {
      addEditorStyle();
      addCommand(editor);
      bindEditorEvents(editor);
    },

    afterInit: function(editor) {
      addFilters(editor);
    }
  });

  function addEditorStyle() {
    CKEDITOR.addCss([
      '.cke_editable ul.' + TASK_LIST_CLASS + ' { list-style: none; padding-left: 0; }',
      '.cke_editable li.' + TASK_ITEM_CLASS + ' { list-style: none; }',
      '.cke_editable ul.' + TASK_LIST_CLASS + ' > li.' + TASK_ITEM_CLASS + ' {',
      '  position: relative;',
      '  padding-left: 2rem;',
      '}',
      '.cke_editable .' + TASK_TOGGLE_CLASS + ' { display: none !important; }',
      '.cke_editable ul.' + TASK_LIST_CLASS + ' > li.' + TASK_ITEM_CLASS + '::before {',
      '  content: "";',
      '  position: absolute;',
      '  top: 0.1rem;',
      '  left: 0;',
      '  width: 1.25rem;',
      '  height: 1.25rem;',
      '  border: 1px solid currentColor;',
      '  border-radius: 0.2em;',
      '  background: transparent;',
      '  box-sizing: border-box;',
      '}',
      '.cke_editable ul.' + TASK_LIST_CLASS + ' > li.' + TASK_ITEM_CLASS + '.' +
        TASK_ITEM_CHECKED_CLASS + '::after {',
      '  content: "";',
      '  position: absolute;',
      '  top: 0.38rem;',
      '  left: 0.32rem;',
      '  width: 0.62rem;',
      '  height: 0.36rem;',
      '  border-left: 2px solid currentColor;',
      '  border-bottom: 2px solid currentColor;',
      '  transform: rotate(-45deg);',
      '  box-sizing: border-box;',
      '}',
      '.cke_editable ul.' + TASK_LIST_CLASS + ' > li.' + TASK_ITEM_CHECKED_CLASS + ' {',
      '  color: #7a7a7a;',
      '}'
    ].join(''));
  }

  function addFilters(editor) {
    var dataProcessor = editor.dataProcessor;
    if (!dataProcessor) {
      return;
    }

    dataProcessor.dataFilter.addRules({
      elements: {
        ul: function(element) {
          return importList(element, false);
        },
        ol: function(element) {
          return importList(element, true);
        }
      }
    }, {priority: 5});

    dataProcessor.htmlFilter.addRules({
      elements: {
        ul: function(element) {
          return exportList(element);
        },
        ol: function(element) {
          return exportList(element);
        }
      }
    }, {priority: 5, applyToAll: true});
  }

  function addCommand(editor) {
    editor.addCommand('xwiki-tasklist', {
      exec: function(editorInstance) {
        toggleTaskList(editorInstance);
      },

      refresh: function(editorInstance, path) {
        var list = getActiveList(editorInstance, path);
        this.setState(isTaskList(list) ? CKEDITOR.TRISTATE_ON : CKEDITOR.TRISTATE_OFF);
      }
    });
  }

  function bindEditorEvents(editor) {
    var normalize = CKEDITOR.tools.debounce(function() {
      normalizeTaskLists(editor);
    }, 50);

    editor.on('contentDom', function() {
      removeTaskToggleButtonsFromEditable(editor);
      normalizeTaskLists(editor);
      attachClickListener(editor);
    });
    editor.on('change', normalize);
    editor.on('afterCommandExec', function(event) {
      if (['bulletedlist', 'numberedlist', 'indent', 'outdent'].indexOf(event.data.name) >= 0) {
        normalize();
      }
    });
    editor.on('key', function(event) {
      if (event.data.keyCode === 13) {
        setTimeout(function() {
          ensureCurrentTaskItem(editor);
        }, 0);
      }
    });
  }

  function toggleTaskList(editor) {
    var list;

    editor.focus();
    editor.fire('saveSnapshot');

    list = getActiveList(editor);
    if (!list) {
      editor.execCommand('bulletedlist');
      list = getActiveList(editor);
    }
    if (!list) {
      return;
    }

    if (!list.is('ul') && typeof list.renameNode === 'function') {
      list.renameNode('ul');
    }

    if (isTaskList(list)) {
      untaskifyList(list);
    } else {
      taskifyList(list);
    }

    normalizeTaskLists(editor);
    editor.fire('saveSnapshot');
    editor.fire('change');
  }

  function attachClickListener(editor) {
    var editable = editor.editable();

    if (!editable || editable.getCustomData('xwiki-tasklist-click-listener')) {
      return;
    }

    editable.setCustomData('xwiki-tasklist-click-listener', true);
    editable.attachListener(editable, 'click', function(event) {
      var target = event.data.getTarget();
      var listItem = target.getAscendant('li', true);
      var nativeEvent;
      var rect;

      if (!listItem || !isTaskListItem(listItem) || !isTaskList(getParentList(listItem))) {
        return;
      }

      nativeEvent = event.data.$;
      rect = listItem.$.getBoundingClientRect();
      if (nativeEvent.clientX > rect.left + CLICK_TOGGLE_OFFSET) {
        return;
      }

      event.data.preventDefault(true);
      editor.fire('saveSnapshot');
      setTaskItemState(listItem, !isCheckedTaskItem(listItem));
      editor.fire('saveSnapshot');
      editor.fire('change');
    });
  }

  function ensureCurrentTaskItem(editor) {
    var list = getActiveList(editor);
    var path;
    var listItem;

    if (!isTaskList(list)) {
      return;
    }

    path = new CKEDITOR.dom.elementPath(editor.getSelection().getStartElement());
    listItem = path.contains('li', 1);
    if (!listItem || isTaskListItem(listItem)) {
      return;
    }

    editor.fire('saveSnapshot');
    setTaskItemState(listItem, false);
    editor.fire('saveSnapshot');
    editor.fire('change');
  }

  function getActiveList(editor, path) {
    var listItem;

    path = path || new CKEDITOR.dom.elementPath(editor.getSelection().getStartElement());
    listItem = path.contains('li', 1);
    return listItem ? getParentList(listItem) : null;
  }

  function getParentList(listItem) {
    return listItem.getAscendant(function(element) {
      return element.is && (element.is('ul') || element.is('ol'));
    }, true);
  }

  function isTaskList(list) {
    return !!list && list.is && list.is('ul') && list.hasClass(TASK_LIST_CLASS);
  }

  function isTaskListItem(listItem) {
    return !!listItem && listItem.hasClass(TASK_ITEM_CLASS);
  }

  function isCheckedTaskItem(listItem) {
    return isTaskListItem(listItem) && listItem.hasClass(TASK_ITEM_CHECKED_CLASS);
  }

  function taskifyList(list) {
    list.addClass(TASK_LIST_CLASS);
    getDirectListItems(list).forEach(function(listItem) {
      setTaskItemState(listItem, false);
    });
  }

  function untaskifyList(list) {
    list.removeClass(TASK_LIST_CLASS);
    getDirectListItems(list).forEach(clearTaskItemState);
  }

  function normalizeTaskLists(editor) {
    var editable = editor.editable();

    if (!editable) {
      return;
    }

    removeTaskToggleButtonsFromEditable(editor);
    editable.find('ul').toArray().forEach(normalizeTaskList);
    editable.find('ol').toArray().forEach(normalizeTaskList);
  }

  function normalizeTaskList(list) {
    var listItems = getDirectListItems(list);
    var isTask = list.is('ul') && hasTaskListSemantics(list, listItems);

    toggleClass(list, TASK_LIST_CLASS, isTask);
    listItems.forEach(function(listItem) {
      if (isTask) {
        if (!isTaskListItem(listItem)) {
          setTaskItemState(listItem, false);
        }
      } else {
        clearTaskItemState(listItem);
      }
    });
  }

  function setTaskItemState(listItem, checked) {
    listItem.addClass(TASK_ITEM_CLASS);
    toggleClass(listItem, TASK_ITEM_CHECKED_CLASS, checked);
  }

  function clearTaskItemState(listItem) {
    listItem.removeClass(TASK_ITEM_CLASS);
    listItem.removeClass(TASK_ITEM_CHECKED_CLASS);
  }

  function importList(list, ordered) {
    var listItems = getChildListElements(list);
    var taskStates = listItems.map(readTaskStateFromListItem);
    var isTask = !ordered && hasImportedTaskListSemantics(list, listItems, taskStates);

    removeClassName(list, TASK_LIST_CLASS);
    listItems.forEach(removeTaskToggleButtons);
    if (!isTask) {
      listItems.forEach(clearImportedTaskState);
      return list;
    }

    addClassName(list, TASK_LIST_CLASS);
    listItems.forEach(function(listItem, index) {
      applyImportedTaskState(listItem, taskStates[index]);
    });
    return list;
  }

  function exportList(list) {
    getChildListElements(list).forEach(removeTaskToggleButtons);
    if (!hasClassName(list, TASK_LIST_CLASS)) {
      getChildListElements(list).forEach(clearExportedTaskState);
      return list;
    }

    getChildListElements(list).forEach(function(listItem) {
      var checked = hasClassName(listItem, TASK_ITEM_CHECKED_CLASS);
      ensureMarkerOnListItem(listItem, checked);
      addClassName(listItem, TASK_ITEM_CLASS);
    });
    return list;
  }

  function readTaskStateFromListItem(listItem) {
    var textNode = getMarkerTextNode(listItem);
    var text;

    if (!textNode) {
      return null;
    }

    text = textNode.value || '';
    if (startsWith(text, UNCHECKED_MARKER)) {
      textNode.value = text.substring(UNCHECKED_MARKER.length);
      return false;
    }
    if (startsWith(text, CHECKED_MARKER)) {
      textNode.value = text.substring(CHECKED_MARKER.length);
      return true;
    }
    return null;
  }

  function applyImportedTaskState(listItem, checked) {
    addClassName(listItem, TASK_ITEM_CLASS);
    toggleClassName(listItem, TASK_ITEM_CHECKED_CLASS, checked === true);
  }

  function clearImportedTaskState(listItem) {
    removeClassName(listItem, TASK_ITEM_CLASS);
    removeClassName(listItem, TASK_ITEM_CHECKED_CLASS);
  }

  function clearExportedTaskState(listItem) {
    removeTaskToggleButtons(listItem);
    removeMarkerFromListItem(listItem);
    clearImportedTaskState(listItem);
  }

  function ensureMarkerOnListItem(listItem, checked) {
    var textNode = getMarkerTextNode(listItem, true);
    var marker = checked ? CHECKED_MARKER : UNCHECKED_MARKER;
    var text = textNode.value || '';

    if (startsWith(text, UNCHECKED_MARKER) || startsWith(text, CHECKED_MARKER)) {
      textNode.value = marker + text.substring(UNCHECKED_MARKER.length);
    } else {
      textNode.value = marker + text;
    }
  }

  function removeMarkerFromListItem(listItem) {
    var textNode = getMarkerTextNode(listItem);
    var text;

    if (!textNode) {
      return;
    }

    text = textNode.value || '';
    if (startsWith(text, UNCHECKED_MARKER) || startsWith(text, CHECKED_MARKER)) {
      textNode.value = text.substring(UNCHECKED_MARKER.length);
    }
  }

  function getMarkerTextNode(container, create) {
    var index;
    var child;
    var descendant;
    var textNode;

    for (index = 0; index < (container.children || []).length; index += 1) {
      child = container.children[index];
      if (child.type === CKEDITOR.NODE_TEXT) {
        return child;
      }
      if (child.type === CKEDITOR.NODE_ELEMENT && child.name !== 'ul' && child.name !== 'ol') {
        descendant = getMarkerTextNode(child, false);
        if (descendant) {
          return descendant;
        }
      }
    }

    if (!create) {
      return null;
    }

    textNode = new CKEDITOR.htmlParser.text('');
    container.children = container.children || [];
    container.children.unshift(textNode);
    textNode.parent = container;
    return textNode;
  }

  function getChildListElements(list) {
    return (list.children || []).filter(function(child) {
      return child.type === CKEDITOR.NODE_ELEMENT && child.name === 'li';
    });
  }

  function removeTaskToggleButtons(container) {
    container.children = (container.children || []).filter(function(child) {
      return !isTaskToggleButton(child);
    });
    container.children.forEach(function(child) {
      if (child.type === CKEDITOR.NODE_ELEMENT) {
        removeTaskToggleButtons(child);
      }
    });
  }

  function isTaskToggleButton(element) {
    return element.type === CKEDITOR.NODE_ELEMENT && element.name === 'button' &&
      hasClassName(element, TASK_TOGGLE_CLASS);
  }

  function getDirectListItems(list) {
    return list.getChildren().toArray().filter(function(child) {
      return child.type === CKEDITOR.NODE_ELEMENT && child.is('li');
    });
  }

  function hasTaskListSemantics(list, listItems) {
    return listItems.length > 0 && list.hasClass(TASK_LIST_CLASS);
  }

  function hasImportedTaskListSemantics(list, listItems, taskStates) {
    return listItems.length > 0 && (hasClassName(list, TASK_LIST_CLASS) || listItems.some(function(listItem, index) {
      return taskStates[index] !== null || hasClassName(listItem, TASK_ITEM_CLASS);
    }));
  }

  function removeTaskToggleButtonsFromEditable(editor) {
    var editable = editor.editable();

    if (!editable) {
      return;
    }

    editable.find('.' + TASK_TOGGLE_CLASS).toArray().forEach(function(button) {
      button.remove();
    });
  }

  function getElementText(element) {
    var buffer = '';

    (element.children || []).forEach(function(child) {
      if (child.type === CKEDITOR.NODE_TEXT) {
        buffer += child.value || '';
      } else if (child.type === CKEDITOR.NODE_ELEMENT && child.name !== 'ul' && child.name !== 'ol') {
        buffer += getElementText(child);
      }
    });
    return buffer;
  }

  function startsWith(text, prefix) {
    return text.lastIndexOf(prefix, 0) === 0;
  }

  function hasClassName(element, className) {
    return (' ' + (element.attributes && element.attributes['class'] || '') + ' ').indexOf(' ' + className + ' ') >= 0;
  }

  function addClassName(element, className) {
    var classNames = getClassNames(element);

    if (classNames.indexOf(className) < 0) {
      classNames.push(className);
      setClassNames(element, classNames);
    }
  }

  function removeClassName(element, className) {
    setClassNames(element, getClassNames(element).filter(function(name) {
      return name !== className;
    }));
  }

  function toggleClassName(element, className, enabled) {
    if (enabled) {
      addClassName(element, className);
    } else {
      removeClassName(element, className);
    }
  }

  function getClassNames(element) {
    var value = element.attributes && element.attributes['class'];

    return value ? value.split(/\s+/).filter(Boolean) : [];
  }

  function setClassNames(element, classNames) {
    element.attributes = element.attributes || {};
    if (classNames.length > 0) {
      element.attributes['class'] = classNames.join(' ');
    } else {
      delete element.attributes['class'];
    }
  }

  function toggleClass(element, className, enabled) {
    if (enabled) {
      element.addClass(className);
    } else {
      element.removeClass(className);
    }
  }
})();
