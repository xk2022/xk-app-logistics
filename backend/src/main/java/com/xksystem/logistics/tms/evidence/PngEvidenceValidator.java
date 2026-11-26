package com.xksystem.logistics.tms.evidence;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Set;
import java.util.zip.CRC32;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;

/** Bounded structural PNG validation, not malware, legal or operational evidence approval. */
public final class PngEvidenceValidator {
    private PngEvidenceValidator() {}
    private static final byte[] SIGNATURE={(byte)137,80,78,71,13,10,26,10};
    public static void validate(byte[] bytes,ExecutionEvidencePolicy.Requirements rule) {
        try {
            if(bytes.length==0||bytes.length>rule.maxBytes()||bytes.length<33||!Arrays.equals(Arrays.copyOf(bytes,8),SIGNATURE))throw invalid();
            int position=8,width=0,height=0;boolean first=true,idat=false,end=false,afterData=false,plte=false;
            while(position<bytes.length) {
                if(bytes.length-position<12)throw invalid();
                long length=Integer.toUnsignedLong(ByteBuffer.wrap(bytes,position,4).getInt());
                if(length>bytes.length-position-12)throw invalid();int size=(int)length;
                String type=new String(bytes,position+4,4,StandardCharsets.US_ASCII);
                if(!type.matches("[A-Za-z]{4}")||Character.isLowerCase(type.charAt(2)))throw invalid();
                CRC32 crc=new CRC32();crc.update(bytes,position+4,size+4);
                if(crc.getValue()!=Integer.toUnsignedLong(ByteBuffer.wrap(bytes,position+8+size,4).getInt()))throw invalid();
                if(first&&!type.equals("IHDR"))throw invalid();
                if(type.equals("IHDR")) {
                    if(!first||size!=13)throw invalid();
                    width=ByteBuffer.wrap(bytes,position+8,4).getInt();height=ByteBuffer.wrap(bytes,position+12,4).getInt();
                    if(width<=0||height<=0||(long)width*height>rule.maxPixels())throw invalid();
                    if(bytes[position+18]!=0||bytes[position+19]!=0||(bytes[position+20]!=0&&bytes[position+20]!=1))throw invalid();
                } else if(type.equals("PLTE")) {
                    if(plte||idat||size==0||size>768||size%3!=0)throw invalid();plte=true;
                } else if(type.equals("IDAT")) {
                    if(afterData)throw invalid();idat=true;
                } else if(type.equals("IEND")) {
                    if(!idat||size!=0||position+12!=bytes.length)throw invalid();end=true;
                } else {
                    if(Character.isUpperCase(type.charAt(0))||Set.of("acTL","fcTL","fdAT").contains(type))throw invalid();
                    if(idat)afterData=true;
                }
                first=false;position+=size+12;
            }
            if(!end)throw invalid();
            var readers=ImageIO.getImageReadersByFormatName("png");if(!readers.hasNext())throw new IllegalStateException("PNG decoder unavailable");
            var reader=readers.next();
            try(var input=new MemoryCacheImageInputStream(new ByteArrayInputStream(bytes))) {
                reader.setInput(input,true,true);
                if(reader.getWidth(0)!=width||reader.getHeight(0)!=height)throw invalid();
                var image=reader.read(0);if(image==null||image.getWidth()!=width||image.getHeight()!=height)throw invalid();
            } finally {reader.dispose();}
        } catch(EvidenceFailure failure){throw failure;}
        catch(java.io.IOException|IllegalArgumentException failure){throw invalid();}
    }
    private static EvidenceFailure invalid(){return EvidenceFailure.invalid("photo","照片格式、內容或尺寸不符合目前規則");}
}
