package bayou.websocket;

import _bayou._str._HexUtil;

class WsOp
{
    final static int continue_ = 0x0;

    final static int text      = 0x1;
    final static int binary    = 0x2;

    final static int close     = 0x8;
    final static int ping      = 0x9;
    final static int pong      = 0xA;

    static String[] strings = new String[16];
    static
    {
        for(int i=0; i<strings.length; i++)
            strings[i] = "0x"+ _HexUtil.byte2hex((byte)i).toUpperCase();

        strings[continue_] = "Continue";
        strings[text] = "Text";
        strings[binary] = "Binary";
        strings[close] = "Close";
        strings[ping] = "Ping";
        strings[pong] = "Pong";
    }
}
