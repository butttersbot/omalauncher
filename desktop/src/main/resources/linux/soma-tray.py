#!/usr/bin/env python3
"""Soma's tray icon on Linux (Boss, 2026-10-29: "Yes, add a Waybar icon"): a StatusNotifierItem with a com.canonical.dbusmenu
menu, the Mac tray's own items (Open Soma, Dashboard, Settings…, Look, Car, Grok, Quit Soma). Java's tray speaks XEmbed,
which Waybar doesn't show; this speaks D-Bus, which it does.

Soma starts it (LinuxTray.kt) with SOMA_TRAY_PORT (its local hand-off port), SOMA_TRAY_TOKEN (said with every action, so no
other program on the machine can drive Soma through it), SOMA_TRAY_LOOKS (the look ids, comma separated) and
SOMA_TRAY_PARENT (Soma's pid: when Soma is gone, so is this). A click on the icon opens Soma; its menu says the rest.
"""
import os
import socket
import sys

# The menu, as the Mac tray has it: (id, label, word said to Soma, children).
def menu(looks):
    return [
        (1, 'Open Soma', 'show', None),
        (2, 'Dashboard', 'dashboard', None),
        (3, 'Settings…', 'settings', None),
        (4, 'Look', None, [(100 + i, look, 'look ' + look, None) for i, look in enumerate(looks)]),
        (5, 'Car', None, [(51, 'Pair the car', 'car pair', None), (52, 'Unpair the car', 'car unpair', None)]),
        (6, 'Grok', None, [(61, 'Connect Grok', 'grok connect', None), (62, 'Disconnect Grok', 'grok disconnect', None)]),
        (7, None, None, None),  # a separator
        (9, 'Quit Soma', 'quit', None),
    ]


def words(items):
    """Each menu id and the word it says."""
    found = {}
    for item_id, _, word, children in items:
        if word:
            found[item_id] = word
        if children:
            found.update(words(children))
    return found


def say(port, token, word):
    """Tells the running Soma [word], as the tray: true when it answered."""
    try:
        with socket.create_connection(('127.0.0.1', int(port)), timeout=1) as link:
            link.sendall(('tray %s %s\n' % (token, word)).encode())
            return link.makefile().readline().strip() == 'soma'
    except (OSError, ValueError):
        return False


def icon(size):
    """The Mac tray's dot (Soma's blue, #7aa2f7), [size] square, as ARGB32 in network order."""
    out = bytearray()
    middle, radius = (size - 1) / 2, size * 0.32
    for y in range(size):
        for x in range(size):
            inside = (x - middle) ** 2 + (y - middle) ** 2 <= radius ** 2
            out += bytes((255, 0x7a, 0xa2, 0xf7)) if inside else bytes((0, 0, 0, 0))
    return bytes(out)


ITEM_XML = '''<node>
 <interface name="org.kde.StatusNotifierItem">
  <property name="Category" type="s" access="read"/>
  <property name="Id" type="s" access="read"/>
  <property name="Title" type="s" access="read"/>
  <property name="Status" type="s" access="read"/>
  <property name="IconName" type="s" access="read"/>
  <property name="IconPixmap" type="a(iiay)" access="read"/>
  <property name="ToolTip" type="(sa(iiay)ss)" access="read"/>
  <property name="ItemIsMenu" type="b" access="read"/>
  <property name="Menu" type="o" access="read"/>
  <method name="Activate"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
  <method name="SecondaryActivate"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
  <method name="ContextMenu"><arg name="x" type="i" direction="in"/><arg name="y" type="i" direction="in"/></method>
  <method name="Scroll"><arg name="delta" type="i" direction="in"/><arg name="orientation" type="s" direction="in"/></method>
  <signal name="NewIcon"/><signal name="NewStatus"><arg type="s"/></signal>
 </interface>
</node>'''

MENU_XML = '''<node>
 <interface name="com.canonical.dbusmenu">
  <property name="Version" type="u" access="read"/>
  <property name="TextDirection" type="s" access="read"/>
  <property name="Status" type="s" access="read"/>
  <method name="GetLayout">
   <arg type="i" name="parentId" direction="in"/><arg type="i" name="recursionDepth" direction="in"/>
   <arg type="as" name="propertyNames" direction="in"/>
   <arg type="u" name="revision" direction="out"/><arg type="(ia{sv}av)" name="layout" direction="out"/>
  </method>
  <method name="GetGroupProperties">
   <arg type="ai" name="ids" direction="in"/><arg type="as" name="propertyNames" direction="in"/>
   <arg type="a(ia{sv})" name="properties" direction="out"/>
  </method>
  <method name="GetProperty">
   <arg type="i" name="id" direction="in"/><arg type="s" name="name" direction="in"/><arg type="v" name="value" direction="out"/>
  </method>
  <method name="Event">
   <arg type="i" name="id" direction="in"/><arg type="s" name="eventId" direction="in"/>
   <arg type="v" name="data" direction="in"/><arg type="u" name="timestamp" direction="in"/>
  </method>
  <method name="EventGroup">
   <arg type="a(isvu)" name="events" direction="in"/><arg type="ai" name="idErrors" direction="out"/>
  </method>
  <method name="AboutToShow"><arg type="i" name="id" direction="in"/><arg type="b" name="needUpdate" direction="out"/></method>
  <method name="AboutToShowGroup">
   <arg type="ai" name="ids" direction="in"/><arg type="ai" name="updatesNeeded" direction="out"/>
   <arg type="ai" name="idErrors" direction="out"/>
  </method>
  <signal name="LayoutUpdated"><arg type="u"/><arg type="i"/></signal>
 </interface>
</node>'''


def props(item_id, label, children):
    """An item's dbusmenu properties, as plain values (made GLib variants by [layout])."""
    if item_id == 0:
        return {'children-display': 'submenu'}
    if label is None:
        return {'type': 'separator'}
    shown = {'label': label}
    if children:
        shown['children-display'] = 'submenu'
    return shown


def run():
    from gi.repository import Gio, GLib

    port, token = os.environ['SOMA_TRAY_PORT'], os.environ['SOMA_TRAY_TOKEN']
    looks = [look for look in os.environ.get('SOMA_TRAY_LOOKS', '').split(',') if look]
    parent = int(os.environ.get('SOMA_TRAY_PARENT', '0'))
    items = menu(looks)
    said = words(items)
    loop = GLib.MainLoop()

    def shown(item_id, label, children):
        return {k: GLib.Variant('s', v) for k, v in props(item_id, label, children).items()}

    def layout(item_id, label, children):
        """An item and everything under it, as dbusmenu's (ia{sv}av)."""
        return (item_id, shown(item_id, label, children), [GLib.Variant('(ia{sv}av)', layout(c[0], c[1], c[3])) for c in children or []])

    def find(item_id, within=items):
        for entry in within:
            if entry[0] == item_id:
                return entry
            if entry[3]:
                inner = find(item_id, entry[3])
                if inner:
                    return inner
        return None

    def on_item(conn, sender, path, iface, method, params, invocation):
        if method in ('Activate', 'SecondaryActivate'):
            say(port, token, 'show')
        invocation.return_value(None)

    def item_property(conn, sender, path, iface, name):
        size = 32
        return {
            'Category': GLib.Variant('s', 'ApplicationStatus'), 'Id': GLib.Variant('s', 'soma'), 'Title': GLib.Variant('s', 'Soma'),
            'Status': GLib.Variant('s', 'Active'), 'IconName': GLib.Variant('s', ''),
            'IconPixmap': GLib.Variant('a(iiay)', [(size, size, icon(size))]),
            'ToolTip': GLib.Variant('(sa(iiay)ss)', ('', [], 'Soma', '')),
            'ItemIsMenu': GLib.Variant('b', False), 'Menu': GLib.Variant('o', '/MenuBar'),
        }.get(name)

    def on_menu(conn, sender, path, iface, method, params, invocation):
        if method == 'GetLayout':
            parent_id = params.unpack()[0]
            entry = (0, '', None, items) if parent_id == 0 else find(parent_id)
            invocation.return_value(GLib.Variant('(u(ia{sv}av))', (1, layout(entry[0], entry[1], entry[3]))))
        elif method == 'GetGroupProperties':
            found = [(i, shown(i, entry[1], entry[3])) for i in params.unpack()[0] for entry in [find(i)] if entry]
            invocation.return_value(GLib.Variant('(a(ia{sv}))', (found,)))
        elif method == 'GetProperty':
            item_id, name = params.unpack()
            entry = find(item_id)
            value = props(item_id, entry[1] if entry else '', entry[3] if entry else None).get(name, '')
            invocation.return_value(GLib.Variant('(v)', (GLib.Variant('s', value),)))
        elif method == 'Event':
            item_id, event = params.unpack()[0:2]
            if event == 'clicked' and item_id in said:
                say(port, token, said[item_id])
            invocation.return_value(None)
        elif method == 'EventGroup':
            for item_id, event, _, _ in params.unpack()[0]:
                if event == 'clicked' and item_id in said:
                    say(port, token, said[item_id])
            invocation.return_value(GLib.Variant('(ai)', ([],)))
        elif method == 'AboutToShow':
            invocation.return_value(GLib.Variant('(b)', (False,)))
        elif method == 'AboutToShowGroup':
            invocation.return_value(GLib.Variant('(aiai)', ([], [])))
        else:
            invocation.return_value(None)

    def menu_property(conn, sender, path, iface, name):
        return {'Version': GLib.Variant('u', 3), 'TextDirection': GLib.Variant('s', 'ltr'), 'Status': GLib.Variant('s', 'normal')}.get(name)

    bus = Gio.bus_get_sync(Gio.BusType.SESSION, None)
    bus.register_object('/StatusNotifierItem', Gio.DBusNodeInfo.new_for_xml(ITEM_XML).interfaces[0], on_item, item_property, None)
    bus.register_object('/MenuBar', Gio.DBusNodeInfo.new_for_xml(MENU_XML).interfaces[0], on_menu, menu_property, None)
    name = 'org.kde.StatusNotifierItem-%d-1' % os.getpid()

    def register(*_):
        # Again whenever the bar's watcher (re)appears: a Waybar restart forgets every item.
        bus.call('org.kde.StatusNotifierWatcher', '/StatusNotifierWatcher', 'org.kde.StatusNotifierWatcher', 'RegisterStatusNotifierItem',
                 GLib.Variant('(s)', (name,)), None, Gio.DBusCallFlags.NONE, -1, None, None)

    Gio.bus_own_name_on_connection(bus, name, Gio.BusNameOwnerFlags.NONE, None, None)
    Gio.bus_watch_name_on_connection(bus, 'org.kde.StatusNotifierWatcher', Gio.BusNameWatcherFlags.NONE, register, None)

    def soma_alive():
        try:
            os.kill(parent, 0)
            return True
        except OSError:
            loop.quit()
            return False

    if parent:
        GLib.timeout_add_seconds(2, soma_alive)
    loop.run()


if __name__ == '__main__':
    sys.exit(run())
