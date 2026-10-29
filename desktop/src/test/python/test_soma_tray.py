"""The Linux tray helper's menu is the Mac tray's (two-way roads), each item says the word Soma acts on, and its icon is
the Mac's dot. Run by LinuxTrayTest; needs only Python (no D-Bus)."""
import importlib.util, pathlib, sys, unittest

HERE = pathlib.Path(__file__).resolve()
SCRIPT = HERE.parents[2] / 'main' / 'resources' / 'linux' / 'soma-tray.py'
spec = importlib.util.spec_from_file_location('soma_tray', SCRIPT)
tray = importlib.util.module_from_spec(spec)
spec.loader.exec_module(tray)


class Menu(unittest.TestCase):
    def test_theMacTraysItemsInItsOrder(self):
        items = tray.menu(['city-pop', 'manga'])
        self.assertEqual(['Open Soma', 'Dashboard', 'Settings…', 'Look', 'Car', 'Grok', None, 'Quit Soma'], [i[1] for i in items])
        looks = items[3][3]
        self.assertEqual(['city-pop', 'manga'], [i[1] for i in looks])
        self.assertEqual(['Pair the car', 'Unpair the car'], [i[1] for i in items[4][3]])
        self.assertEqual(['Connect Grok', 'Disconnect Grok'], [i[1] for i in items[5][3]])

    def test_eachItemSaysTheWordSomaActsOn(self):
        said = tray.words(tray.menu(['city-pop', 'manga']))
        self.assertEqual({1: 'show', 2: 'dashboard', 3: 'settings', 100: 'look city-pop', 101: 'look manga', 51: 'car pair', 52: 'car unpair',
                          61: 'grok connect', 62: 'grok disconnect', 9: 'quit'}, said)
        ids = [i[0] for i in tray.menu(['a', 'b'])] + [c[0] for i in tray.menu(['a', 'b']) for c in (i[3] or [])]
        self.assertEqual(len(ids), len(set(ids)), 'menu ids are unique')

    def test_submenusSeparatorAndIcon(self):
        self.assertEqual({'label': 'Look', 'children-display': 'submenu'}, tray.props(4, 'Look', [(100, 'x', 'look x', None)]))
        self.assertEqual({'type': 'separator'}, tray.props(7, None, None))
        self.assertEqual({'children-display': 'submenu'}, tray.props(0, '', []))
        pixels = tray.icon(32)
        self.assertEqual(32 * 32 * 4, len(pixels))
        middle = (16 * 32 + 16) * 4
        self.assertEqual(bytes((255, 0x7a, 0xa2, 0xf7)), pixels[middle:middle + 4])
        self.assertEqual(bytes((0, 0, 0, 0)), pixels[0:4])

    def test_aSaidWordIsTheTraysWithItsToken(self):
        import socket, threading
        server = socket.socket(); server.bind(('127.0.0.1', 0)); server.listen(1)
        got = []
        def answer():
            conn, _ = server.accept()
            got.append(conn.makefile().readline().strip()); conn.sendall(b'soma\n'); conn.close()
        threading.Thread(target=answer).start()
        self.assertTrue(tray.say(server.getsockname()[1], 'tok123', 'settings'))
        self.assertEqual(['tray tok123 settings'], got)
        server.close()
        self.assertFalse(tray.say(1, 'tok', 'show'))  # nothing listening: no answer, no crash


if __name__ == '__main__':
    unittest.main()
