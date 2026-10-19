package com.starsea.nautilus;

import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import javax.imageio.ImageIO;
import javax.swing.JFrame;

/** The existing Nautilus mark, shared by the UI, window icons and desktop taskbar. */
final class NautilusIcon {
    private static final Color MINT=new Color(0x72DEBF), LAVENDER=new Color(0xB9ABFF), INK=new Color(0x0D111B);
    private NautilusIcon() {}
    static void paint(Graphics2D g,int size,Color start,Color end,Color ink) {
        Theme.antialias(g);
        g.setPaint(new GradientPaint(0,0,start,size,size,end));
        g.fillRoundRect(0,0,size,size,size/3,size/3);
        g.setColor(ink);g.setStroke(new BasicStroke(Math.max(2f,size/22f),BasicStroke.CAP_ROUND,BasicStroke.JOIN_ROUND));
        double center=size/2.0;Path2D path=new Path2D.Double();
        for(int i=0;i<=90;i++){
            double angle=i/90.0*Math.PI*4.6,radius=size*(.04+.28*i/90.0);
            double x=center+Math.cos(angle)*radius,y=center+Math.sin(angle)*radius;
            if(i==0)path.moveTo(x,y);else path.lineTo(x,y);
        }
        g.draw(path);
    }
    static BufferedImage image(int size) {
        if(size<1||size>4096)throw new IllegalArgumentException("Invalid icon size");
        int resolution=Math.max(1024,size);
        BufferedImage source=new BufferedImage(resolution,resolution,BufferedImage.TYPE_INT_ARGB);
        Graphics2D g=source.createGraphics();paint(g,resolution,MINT,LAVENDER,INK);g.dispose();
        if(size==resolution)return source;
        BufferedImage scaled=new BufferedImage(size,size,BufferedImage.TYPE_INT_ARGB);g=scaled.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING,RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(source,0,0,size,size,null);g.dispose();return scaled;
    }
    static List<Image> windowImages(){return List.of(image(16),image(32),image(48),image(64),image(128),image(256),image(512));}
    static void install(JFrame frame) {
        frame.setIconImages(windowImages());
        // On macOS, the Dock icon belongs to the application rather than an individual window.
        if(!Taskbar.isTaskbarSupported())return;
        try{
            Taskbar taskbar=Taskbar.getTaskbar();
            if(taskbar.isSupported(Taskbar.Feature.ICON_IMAGE))taskbar.setIconImage(image(1024));
        }catch(UnsupportedOperationException | SecurityException ignored){ /* Window icons remain available on unsupported desktops. */ }
    }
    static void writeIco(Path target)throws IOException {
        int[] sizes={16,24,32,48,64,128,256};
        java.util.ArrayList<byte[]> images=new java.util.ArrayList<>();int length=6+16*sizes.length;
        for(int size:sizes){java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();ImageIO.write(image(size),"png",bytes);images.add(bytes.toByteArray());length+=bytes.size();}
        java.nio.ByteBuffer buffer=java.nio.ByteBuffer.allocate(length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        buffer.putShort((short)0).putShort((short)1).putShort((short)sizes.length);int offset=6+16*sizes.length;
        for(int index=0;index<sizes.length;index++) {
            int size=sizes[index];byte[] bytes=images.get(index);
            buffer.put((byte)(size==256?0:size)).put((byte)(size==256?0:size)).put((byte)0).put((byte)0).putShort((short)1).putShort((short)32).putInt(bytes.length).putInt(offset);offset+=bytes.length;
        }
        for(byte[] bytes:images)buffer.put(bytes);
        if(target.getParent()!=null)Files.createDirectories(target.getParent());Files.write(target,buffer.array());
    }
    /** Export a copy of the exact application icon; no display or image libraries required. */
    public static void main(String[] args)throws IOException {
        if(args.length==2&&args[0].equals("--ico")){writeIco(Path.of(args[1]));return;}
        if(args.length!=1)throw new IllegalArgumentException("Usage: NautilusIcon <output.png> | --ico <output.ico>");
        Path target=Path.of(args[0]);if(target.getParent()!=null)Files.createDirectories(target.getParent());
        if(!ImageIO.write(image(1024),"png",target.toFile()))throw new IOException("PNG encoder unavailable");
    }
}
