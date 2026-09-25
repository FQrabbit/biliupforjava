(function (window, document) {
    'use strict';

    var raf = window.requestAnimationFrame || function (callback) { return window.setTimeout(callback, 16); };
    var scheduled = false;

    function closestRadioButton(node) {
        var current = node;
        while (current && current !== document.body) {
            if (current.classList && current.classList.contains('el-radio-button')) return current;
            current = current.parentNode;
        }
        return null;
    }

    function updateGroup(group) {
        if (!group || !group.querySelectorAll) return;
        var buttons = group.querySelectorAll('.el-radio-button');
        if (!buttons.length) return;
        group.classList.add('ui-capsule-group');

        var selected = group.querySelector('input.el-radio-button__orig-radio:checked');
        var target = selected ? closestRadioButton(selected) : null;
        if (!target || !target.getBoundingClientRect) {
            group.style.setProperty('--capsule-opacity', '0');
            return;
        }

        var groupRect = group.getBoundingClientRect();
        var targetRect = target.getBoundingClientRect();
        if (!groupRect.width || !targetRect.width) {
            group.style.setProperty('--capsule-opacity', '0');
            return;
        }

        group.style.setProperty('--capsule-x', Math.max(0, targetRect.left - groupRect.left) + 'px');
        group.style.setProperty('--capsule-y', Math.max(0, targetRect.top - groupRect.top) + 'px');
        group.style.setProperty('--capsule-width', targetRect.width + 'px');
        group.style.setProperty('--capsule-height', targetRect.height + 'px');
        group.style.setProperty('--capsule-opacity', '1');
    }

    function updateAll() {
        scheduled = false;
        var groups = document.querySelectorAll('.el-radio-group');
        for (var i = 0; i < groups.length; i++) updateGroup(groups[i]);
    }

    function schedule() {
        if (scheduled) return;
        scheduled = true;
        raf(updateAll);
    }

    function start() {
        schedule();
        document.addEventListener('change', schedule, true);
        window.addEventListener('resize', schedule);
        if (window.MutationObserver && document.body) {
            var observer = new MutationObserver(function (records) {
                for (var i = 0; i < records.length; i++) {
                    if (records[i].type === 'childList' || records[i].attributeName === 'class' || records[i].attributeName === 'checked') {
                        schedule();
                        break;
                    }
                }
            });
            observer.observe(document.body, { childList: true, subtree: true, attributes: true, attributeFilter: ['class', 'checked'] });
        }
        if (window.ResizeObserver) {
            var resizeObserver = new ResizeObserver(schedule);
            var groups = document.querySelectorAll('.el-radio-group');
            for (var i = 0; i < groups.length; i++) resizeObserver.observe(groups[i]);
        }
    }

    window.BiliupCapsuleEnhancer = { update: schedule };
    if (document.readyState === 'loading') {
        document.addEventListener('DOMContentLoaded', start);
    } else {
        start();
    }
}(window, document));
