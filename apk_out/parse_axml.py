import struct, sys

CHUNK_STRING_POOL = 0x0001
CHUNK_XML_START_TAG = 0x0102
TYPES = {0:'NULL',1:'REFERENCE',2:'STRING',3:'INT-DEC',4:'INT-HEX',5:'BOOL',
         16:'DIM',17:'FRAC',18:'FLAGS',28:'COLOR'}

def parse(path):
    data = open(path,'rb').read()

    # --- string pool ---
    ctype, csize, hsize = struct.unpack_from('<HHI', data, 8)
    sp_chunk_start = 8
    string_count, style_count, sp_flags, strings_start, styles_start = struct.unpack_from('<IIIII', data, 8+8)
    offsets = list(struct.unpack_from('<%dI' % string_count, data, 8+8+20))
    str_data_base = sp_chunk_start + strings_start
    is_utf8 = (sp_flags & 0x100) != 0
    strings = []
    for off in offsets:
        pos = str_data_base + off
        if is_utf8:
            utf8len = data[pos]
            char_start = pos + 2  # skip utf8len + utf16len
        else:
            utf8len = struct.unpack_from('<H', data, pos)[0]
            char_start = pos + 4  # skip two u16 lengths
        strings.append(data[char_start:char_start+utf8len].decode('utf-8','replace'))

    def s(idx):
        if idx == 0xFFFFFFFF or idx >= len(strings):
            return '?%d' % idx
        return strings[idx]

    # --- walk chunks to find start tags ---
    pos = 8 + csize
    print("="*60)
    print("ACCESSIBILITY SERVICE CONFIG ATTRIBUTES")
    print("="*60)
    while pos < len(data):
        ctype, csize, hsize = struct.unpack_from('<HHI', data, pos)
        if ctype == CHUNK_XML_START_TAG:
            base = pos + hsize
            nameidx = struct.unpack_from('<I', data, base+4)[0]
            attr_count = struct.unpack_from('<H', data, base+12)[0]
            attr_size = struct.unpack_from('<H', data, base+10)[0]
            print("\n<tag %s>" % s(nameidx))
            abase = pos + hsize + 20  # 20 fixed fields before attrs
            for a in range(attr_count):
                off = abase + a*attr_size
                ans = struct.unpack_from('<I', data, off+0)[0]
                aname = struct.unpack_from('<I', data, off+4)[0]
                araw = struct.unpack_from('<I', data, off+8)[0]
                avsize = struct.unpack_from('<H', data, off+12)[0]
                atype = struct.unpack_from('<B', data, off+15)[0]
                adata = struct.unpack_from('<I', data, off+16)[0]
                isString = (atype == 2)
                val = s(adata) if isString else adata
                tname = TYPES.get(atype, str(atype))
                print("    %s = %r   [type=%s]" % (s(aname), val, tname))
        pos += csize

if __name__=='__main__':
    parse(sys.argv[1])