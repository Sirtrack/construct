package construct;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;

import construct.exception.FieldError;
import construct.exception.SizeofError;
import construct.exception.ValueError;
import construct.lib.Containers.Container;
import construct.lib.Decoder;
import construct.lib.Encoder;
import construct.lib.Resizer;
import static construct.lib.Containers.*;

public class Core {

/*
 * #===============================================================================
 * # abstract constructs
 * #===============================================================================
 */

/**
  The mother of all constructs.

  This object is generally not directly instantiated, and it does not
  directly implement parsing and building, so it is largely only of interest
  to subclass implementors.

  The external user API:

   * parse()
   * parse_stream()
   * build()
   * build_stream()
   * sizeof()

  Subclass authors should not override the external methods. Instead,
  another API is available:

   * _parse()
   * _build()
   * _sizeof()

  There is also a flag API:

   * _set_flag()
   * _clear_flag()
   * _inherit_flags()
   * _is_flag()

  And stateful copying:

   * __getstate__()
   * __setstate__()

  Attributes and Inheritance
  ==========================

  All constructs have a name and flags. The name is used for naming struct
  members and context dictionaries. Note that the name can either be a
  string, or None if the name is not needed. A single underscore ("_") is a
  reserved name, and so are names starting with a less-than character ("<").
  The name should be descriptive, short, and valid as a Python identifier,
  although these rules are not enforced.

  The flags specify additional behavioral information about this construct.
  Flags are used by enclosing constructs to determine a proper course of
  action. Flags are inherited by default, from inner subconstructs to outer
  constructs. The enclosing construct may set new flags or clear existing
  ones, as necessary.

  For example, if FLAG_COPY_CONTEXT is set, repeaters will pass a copy of
  the context for each iteration, which is necessary for OnDemand parsing.
*/
	static public abstract class Construct {
		
    public static final int FLAG_COPY_CONTEXT          = 0x0001;
    public static final int FLAG_DYNAMIC               = 0x0002;
    public static final int FLAG_EMBED                 = 0x0004;
    public static final int FLAG_NESTING               = 0x0008;
		
		int conflags;
		public String name;

		public Construct(String name) {
			this( name, 0 );
		}

		public Construct(String name, int flags) {
			if (name.equals("_") || name.startsWith("<"))
				throw new FieldError("reserved name " + name); // raise
			// ValueError

			this.name = name;
			this.conflags = flags;
		}
		
		@Override
		public String toString(){
			return getClass().getName() + "(" + name + ")";
		}
		/**
        Set the given flag or flags.
		 * @param flag flag to set; may be OR'd combination of flags
		 */
		public void _set_flag(int flag){
			conflags |= flag;
		}
		
		/**
        Clear the given flag or flags.
		 * @param flag flag to clear; may be OR'd combination of flags
		 */
		public void _clear_flag( int flag ){
			conflags &= ~flag;
		}
		
		/**
        Pull flags from subconstructs.
		 */
		public void _inherit_flags( Subconstruct... subcons ){
			for( Subconstruct sc : subcons ){
				_set_flag(sc.conflags);
			}
		}
		
		/**
        Check whether a given flag is set.
		 * @param flag flag to check
		 * @return
		 */
		public boolean _is_flag( int flag ){
			return (conflags & flag) == flag;
		}

		public byte[] _read_stream( ByteBuffer stream, int length) {
			if (length < 0)
				throw new FieldError("length must be >= 0 " + length);
			{
				int len = stream.remaining();
				if (len != length)
					throw new FieldError("expected " + length + " found " + len);
				byte[] out = new byte[length];
				stream.get(out, 0, length);
				return out;
			}
		}

		static public int getDataLength( Object data ){
			if( data instanceof String)
				return ((String)data).length();
			else if( data instanceof Byte )
				return 1;
			else if( data instanceof Integer ){
				int num = (Integer)data;
				if( num < 256 )
					return 1;
				else if( num < 65536 )
					return 2;
  			else
  				return 4;
//  				return Integer.SIZE/8;
			} else if( data instanceof byte[] )
				return ((byte[])data).length;
			else return -1;
		}

		static public void appendDataStream( StringBuilder stream, Object data ){
			if( data instanceof String)
				stream.append((String)data);
			else if( data instanceof Byte )
				appendDataStream( stream, new byte[]{(Byte)data}); //TODO not very elegant
			else if( data instanceof Integer )
				stream.append((Integer)data);
			else if( data instanceof byte[] )
	      try {
	        stream.append( new String((byte[])data, "ISO-8859-1"));
        } catch (UnsupportedEncodingException e) {
        	throw new ValueError( "Can't append data " + e.getMessage());
        }
      else throw new ValueError( "Can't append data " + data);
		}
		
		public void _write_stream( StringBuilder stream, int length, Object data) {
			if (length < 0)
				throw new FieldError("length must be >= 0 " + length);

			int datalength = getDataLength( data );
			if ( length != datalength )
				throw new FieldError("expected " + length + " found " + datalength);

			appendDataStream( stream, data );
		};

		/**
		 * Parse an in-memory buffer.
		 * 
		 * Strings, buffers, memoryviews, and other complete buffers can be parsed with this method.
		 * 
		 * @param data
		 */
		public Object parse(byte[] data) {
			return parse_stream( ByteBuffer.wrap( data ));
		}

		public Object parse(String text) {
			return parse_stream( ByteBuffer.wrap( text.getBytes() ));
		}

		/**
		 * Parse a stream.
		 * 
		 * Files, pipes, sockets, and other streaming sources of data are handled by this method.
		 */
		public Object parse_stream( ByteBuffer stream) {
			return _parse(stream, new Container());
		}

		abstract public Object _parse( ByteBuffer stream, Container context);

		/**
		 * Build an object in memory.
		 * 
		 * @param obj
		 * @return
		 */
		public byte[] build( Object obj) {
			StringBuilder stream = new StringBuilder();
			build_stream(obj, stream);

			return stream.toString().getBytes();
		}

		/**
		 * Build an object directly into a stream.
		 * 
		 * @param obj
		 * @param stream
		 */
		public void build_stream( Object obj, StringBuilder stream) {
			_build(obj, stream, new Container());
		}

		// abstract void _build( String obj, OutputStream stream, Container
		// context);
		protected abstract void _build( Object obj, StringBuilder stream, Container context);

		/**
		 * Calculate the size of this object, optionally using a context. Some constructs have no fixed size and can only know their size for a given hunk of data;
		 * these constructs will raise an error if they are not passed a context.
		 * 
		 * @param context
		 *          contextual data
		 * @return the length of this construct
		 */
		public int sizeof(Container context) {
			if (context == null) {
				context = new Container();
			}
			try {
				return _sizeof(context);
			} catch (Exception e) {
				throw new SizeofError(e);
			}
		}

		public int sizeof() {
			return sizeof(null);
		}

		abstract protected int _sizeof(Container context);
	}

	/**
	 * """ Abstract subconstruct (wraps an inner construct, inheriting its name and flags). """
	 * 
	 */
	public static abstract class Subconstruct extends Construct {

		protected Construct subcon;

		/**
		 * @param name
		 * @param subcon
		 *          the construct to wrap
		 */
		public Subconstruct(Construct subcon) {
			super(subcon.name, subcon.conflags);
			this.subcon = subcon;
		}

		@Override
		public Object _parse( ByteBuffer stream, Container context) {
			return subcon._parse(stream, context);
		}

		@Override
		protected void _build( Object obj, StringBuilder stream, Container context) {
			subcon._build(obj, stream, context);
		}

		@Override
		protected int _sizeof(Container context){
			return subcon._sizeof(context);
		}
	}

	/**
	 * """ Abstract adapter: calls _decode for parsing and _encode for building. """
	 * 
	 */
	public static abstract class Adapter extends Subconstruct {
		/**
		 * @param name
		 * @param subcon
		 *          the construct to wrap
		 */
		public Adapter(Construct subcon) {
			super(subcon);
		}

		@Override
		public Object _parse( ByteBuffer stream, Container context) {
			return _decode(subcon._parse( stream, context ), context);
		}

		public void _build(Object obj, StringBuilder stream, Container context) {
			subcon._build(_encode(obj, context), stream, context);
		}

		abstract public Object _decode(Object obj, Container context);
		abstract public Object _encode(Object obj, Container context);
	}

/*
 * ===============================================================================
 * * Fields
 * ===============================================================================
 */

	/**
	 * A fixed-size byte field.
	 */
	public static class StaticField extends Construct {
		int length;

		/**
		 * @param name
		 *          field name
		 * @param length
		 *          number of bytes in the field
		 */
		public StaticField(String name, int length) {
			super(name);
			this.length = length;
		}

		@Override
		public Object _parse( ByteBuffer stream, Container context) {
			return _read_stream( stream, length);
		}

		@Override
		protected void _build( Object obj, StringBuilder stream, Container context) {
			_write_stream(stream, length, obj);
		}
		
		@Override
    protected int _sizeof(Container context) {
			return length;
    }

		/*
		  * public int _sizeof( Container context ){ return length; }
		  */
	}

	/**
	 * A field that uses ``struct`` to pack and unpack data.
	 * 
	 * See ``struct`` documentation for instructions on crafting format strings.
	 */
	public static class FormatField extends StaticField {
		int length;
		Packer packer;

		/**
		 * @param name
		 *          name of the field
		 * @param endianness
		 *          : format endianness string; one of "<", ">", or "="
		 * @param format
		 *          : a single format character
		 */
		public FormatField(String name, char endianity, char format) {
			super(name, 0);

			if (endianity != '>' && endianity != '<' && endianity != '=')
				throw new ValueError("endianity must be be '=', '<', or '>' " + endianity);

			packer = new Packer(endianity, format);
			super.length = packer.length();

		}

		@Override
		public Object _parse( ByteBuffer stream, Container context ) {
			try {
				return packer.unpack(stream)[0];
			} catch (Exception e) {
				throw new FieldError(e);
			}
		}

		@Override
		public void _build( Object obj, StringBuilder stream, Container context) {
			_write_stream(stream, super.length, packer.pack(obj));
		}

	}

/*
 * #===============================================================================
 * # structures and sequences
 * #===============================================================================
 */
	/**
    A sequence of named constructs, similar to structs in C. The elements are
    parsed and built in the order they are defined.
    See also Embedded.
    Example:
    Struct("foo",
        UBInt8("first_element"),
        UBInt16("second_element"),
        Padding(2),
        UBInt8("third_element"),
    )
	 */
	static public Struct Struct(String name, Construct... subcons){
		return new Struct( name, subcons );
	}
	static public class Struct extends Construct{
		public boolean nested = true;
		Construct[] subcons;
		/**
		 * @param name the name of the structure
		 * @param subcons a sequence of subconstructs that make up this structure.
		 */
		public Struct(String name, Construct... subcons) {
	    super(name);
	    this.subcons = subcons;
/*
        self._inherit_flags(*subcons)
        self._clear_flag(self.FLAG_EMBED)
 * */
	    }

		@Override
		public Object _parse( ByteBuffer stream, Container context) {
			
			Container obj;
			if( context.contains("<obj>")){
				obj = (Container)context.get("<obj>");
				context.del("<obj>");
			} else{
				obj = new Container();
				if( nested ){
					context = Container( P("_", context) );
				}
			}

			for( Construct sc: subcons ){
				if( (sc.conflags & FLAG_EMBED) != 0 ){
					context.set("<obj>", obj);
					sc._parse(stream, context);
				} else if( sc.name != null ){
					  Object subobj = sc._parse(stream, context);
						obj.set( sc.name, subobj );
						context.set( sc.name, subobj );
				}
			}
			return obj;
    }

		@Override
    protected void _build( Object obj, StringBuilder stream, Container context ) {
			if( context.contains("<unnested>")){
				context.del("<unnested>");
			} else if( nested ){
				context = Container( P("_", context ));
			}
			for( Construct sc: subcons){
				Object subobj;
				if( (sc.conflags & FLAG_EMBED) != 0 ){
					context.set( "<unnested>", true );
					subobj = obj;
				} else if( sc.name == null ){
					subobj = null;
				} else if( obj instanceof Container ){
					Container container = (Container)obj;
					subobj = container.get( sc.name );
					context.set(sc.name, subobj);
				} else
						continue;
				
				sc._build(subobj, stream, context);
			}
    }

		@Override
    protected int _sizeof(Container context) {
        int sum = 0;
				if( nested )
            context = Container( P("_", context) );
        
        for( Construct sc: subcons ){
        	sum += sc._sizeof(context);
        }
        
        return sum;
    }
	}

/*
#===============================================================================
# stream manipulation
#===============================================================================
*/
	/**
    Creates an in-memory buffered stream, which can undergo encoding and
    decoding prior to being passed on to the subconstruct.
    See also Bitwise.

    Note:
    * Do not use pointers inside Buffered

    Example:
    Buffered(BitField("foo", 16),
        encoder = decode_bin,
        decoder = encode_bin,
        resizer = lambda size: size / 8,
    )
	 */
	static public class Buffered extends Subconstruct{
		Encoder encoder;
		Decoder decoder;
		Resizer resizer;
		/**
		 * @param subcon the subcon which will operate on the buffer
		 * @param encoder a function that takes a string and returns an encoded
      string (used after building)
		 * @param decoder a function that takes a string and returns a decoded
      string (used before parsing)
		 * @param resizer a function that takes the size of the subcon and "adjusts"
      or "resizes" it according to the encoding/decoding process.
		 */
		public Buffered( Construct subcon, Encoder encoder, Decoder decoder, Resizer resizer ) {
	    super(subcon);
	    this.encoder = encoder;
	    this.decoder = decoder;
	    this.resizer = resizer;
    }
		@Override
		public Object _parse( ByteBuffer stream, Container context) {
      byte[] data = _read_stream(stream, _sizeof(context));
      String stream2 = decoder.decode(data);
			return subcon._parse(ByteBuffer.wrap( stream2.getBytes() ), context);
		}

		@Override
		protected void _build( Object obj, StringBuilder stream, Container context) {
			int size = _sizeof(context);
			StringBuilder stream2 = new StringBuilder();
			subcon._build(obj, stream2, context);
			byte[] data = encoder.encode(stream2.toString());
			if( data.length != size )
				throw new RuntimeException( "Wrong data length: " + data.length );
			_write_stream(stream, size, data);
		}
		@Override
    protected int _sizeof(Container context) {
			return resizer.resize( subcon._sizeof(context));
    }
	}
/*
class Pointer(Subconstruct):
    """
    Changes the stream position to a given offset, where the construction
    should take place, and restores the stream position when finished.
    See also Anchor, OnDemand and OnDemandPointer.

    Notes:
    * requires a seekable stream.

    Parameters:
    * offsetfunc: a function that takes the context and returns an absolute
      stream position, where the construction would take place
    * subcon - the subcon to use at `offsetfunc()`

    Example:
    Struct("foo",
        UBInt32("spam_pointer"),
        Pointer(lambda ctx: ctx.spam_pointer,
            Array(5, UBInt8("spam"))
        )
    )
    """
    __slots__ = ["offsetfunc"]
    def __init__(self, offsetfunc, subcon):
        Subconstruct.__init__(self, subcon)
        self.offsetfunc = offsetfunc
    def _parse(self, stream, context):
        newpos = self.offsetfunc(context)
        origpos = stream.tell()
        stream.seek(newpos)
        obj = self.subcon._parse(stream, context)
        stream.seek(origpos)
        return obj
    def _build(self, obj, stream, context):
        newpos = self.offsetfunc(context)
        origpos = stream.tell()
        stream.seek(newpos)
        self.subcon._build(obj, stream, context)
        stream.seek(origpos)
    def _sizeof(self, context):
        return 0

class Peek(Subconstruct):
    """
    Peeks at the stream: parses without changing the stream position.
    See also Union. If the end of the stream is reached when peeking,
    returns None.

    Notes:
    * requires a seekable stream.

    Parameters:
    * subcon - the subcon to peek at
    * perform_build - whether or not to perform building. by default this
      parameter is set to False, meaning building is a no-op.

    Example:
    Peek(UBInt8("foo"))
    """
    __slots__ = ["perform_build"]
    def __init__(self, subcon, perform_build = False):
        Subconstruct.__init__(self, subcon)
        self.perform_build = perform_build
    def _parse(self, stream, context):
        pos = stream.tell()
        try:
            return self.subcon._parse(stream, context)
        except FieldError:
            pass
        finally:
            stream.seek(pos)
    def _build(self, obj, stream, context):
        if self.perform_build:
            self.subcon._build(obj, stream, context)
    def _sizeof(self, context):
        return 0

class OnDemand(Subconstruct):
    """
    Allows for on-demand (lazy) parsing. When parsing, it will return a
    LazyContainer that represents a pointer to the data, but does not actually
    parses it from stream until it's "demanded".
    By accessing the 'value' property of LazyContainers, you will demand the
    data from the stream. The data will be parsed and cached for later use.
    You can use the 'has_value' property to know whether the data has already
    been demanded.
    See also OnDemandPointer.

    Notes:
    * requires a seekable stream.

    Parameters:
    * subcon -
    * advance_stream - whether or not to advance the stream position. by
      default this is True, but if subcon is a pointer, this should be False.
    * force_build - whether or not to force build. If set to False, and the
      LazyContainer has not been demaned, building is a no-op.

    Example:
    OnDemand(Array(10000, UBInt8("foo"))
    """
    __slots__ = ["advance_stream", "force_build"]
    def __init__(self, subcon, advance_stream = True, force_build = True):
        Subconstruct.__init__(self, subcon)
        self.advance_stream = advance_stream
        self.force_build = force_build
    def _parse(self, stream, context):
        obj = LazyContainer(self.subcon, stream, stream.tell(), context)
        if self.advance_stream:
            stream.seek(self.subcon._sizeof(context), 1)
        return obj
    def _build(self, obj, stream, context):
        if not isinstance(obj, LazyContainer):
            self.subcon._build(obj, stream, context)
        elif self.force_build or obj.has_value:
            self.subcon._build(obj.value, stream, context)
        elif self.advance_stream:
            stream.seek(self.subcon._sizeof(context), 1)

class Restream(Subconstruct):
    """
    Wraps the stream with a read-wrapper (for parsing) or a
    write-wrapper (for building). The stream wrapper can buffer the data
    internally, reading it from- or writing it to the underlying stream
    as needed. For example, BitStreamReader reads whole bytes from the
    underlying stream, but returns them as individual bits.
    See also Bitwise.

    When the parsing or building is done, the stream's close method
    will be invoked. It can perform any finalization needed for the stream
    wrapper, but it must not close the underlying stream.

    Note:
    * Do not use pointers inside Restream

    Parameters:
    * subcon - the subcon
    * stream_reader - the read-wrapper
    * stream_writer - the write wrapper
    * resizer - a function that takes the size of the subcon and "adjusts"
      or "resizes" it according to the encoding/decoding process.

    Example:
    Restream(BitField("foo", 16),
        stream_reader = BitStreamReader,
        stream_writer = BitStreamWriter,
        resizer = lambda size: size / 8,
    )
    """
    __slots__ = ["stream_reader", "stream_writer", "resizer"]
    def __init__(self, subcon, stream_reader, stream_writer, resizer):
        Subconstruct.__init__(self, subcon)
        self.stream_reader = stream_reader
        self.stream_writer = stream_writer
        self.resizer = resizer
    def _parse(self, stream, context):
        stream2 = self.stream_reader(stream)
        obj = self.subcon._parse(stream2, context)
        stream2.close()
        return obj
    def _build(self, obj, stream, context):
        stream2 = self.stream_writer(stream)
        self.subcon._build(obj, stream2, context)
        stream2.close()
    def _sizeof(self, context):
        return self.resizer(self.subcon._sizeof(context))
 */
	
/*
#===============================================================================
# miscellaneous
#===============================================================================
*/
	
	static public final PassClass Pass = PassClass.getInstance();
	
	/**
    """
    A do-nothing construct, useful as the default case for Switch, or
    to indicate Enums.
    See also Switch and Enum.

    Notes:
    * this construct is a singleton. do not try to instatiate it, as it
      will not work...

    Example:
    Pass
	 */
	static private class PassClass extends Construct{
		private static PassClass instance;
		
		private PassClass(String name) {
	    super(name);
    }

		public static synchronized construct.Core.PassClass getInstance() {
	    if( instance == null )
	    	instance = new PassClass("Pass"); // TODO should use a null name
	    return instance;
    }

		@Override
    public Object _parse(ByteBuffer stream, Container context) {
	    return null;
    }

		@Override
    protected void _build(Object obj, StringBuilder stream, Container context) {
	    // assert obj is None
    }

		@Override
    protected int _sizeof(Container context) {
	    return 0;
    }
		
		
	}
}
