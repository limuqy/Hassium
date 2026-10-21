package io.github.limuqy.mc.hassium.client.sleep;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class SleepSetupScreen extends Screen {
    private final Screen parent; private EditBox health; private Button enter,save;
    private int autoSeconds;
    public SleepSetupScreen(Screen parent) { super(Component.translatable("hassium.sleep.title")); this.parent=parent; }
    @Override protected void init() {
        String oldHealth=health==null ? Float.toString(SleepProfiles.threshold()) : health.getValue();
        if(health==null) autoSeconds=SleepProfiles.autoSeconds();
        health=new EditBox(font,width/2-70,height/2-65,140,20,Component.translatable("hassium.sleep.threshold"));
        health.setValue(oldHealth); addRenderableWidget(health);
        addRenderableWidget(Button.builder(autoLabel(),b->{
            autoSeconds=switch(autoSeconds) { case 0 -> 180; case 180 -> 300; case 300 -> 600; default -> 0; };
            b.setMessage(autoLabel());
        }).bounds(width/2-100,height/2-6,200,20).build());
        save=addRenderableWidget(Button.builder(Component.translatable("hassium.sleep.save"),b->{
            if(savePreferences()) minecraft.setScreen(null);
        }).bounds(width/2-100,height/2+20,200,20).build());
        enter=addRenderableWidget(Button.builder(Component.translatable("hassium.sleep.enter"),b->{
            if(savePreferences()) SleepClient.enter(Float.parseFloat(health.getValue()));
        }).bounds(width/2-100,height/2+46,200,20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),b->onClose()).bounds(width/2-100,height/2+72,200,20).build());
    }
    private Component autoLabel() {
        return autoSeconds==0 ? Component.translatable("hassium.sleep.auto_off")
                : Component.translatable("hassium.sleep.auto_after",autoSeconds/60);
    }
    private boolean savePreferences() {
        try {
            float value=Float.parseFloat(health.getValue());
            if(minecraft.player==null || !io.github.limuqy.mc.hassium.protocol.sleep.SleepControl.validThreshold(value,minecraft.player.getMaxHealth())) return false;
            SleepProfiles.save(value,autoSeconds); return true;
        } catch(NumberFormatException e) { return false; }
    }
    @Override public void tick() {
        try { float f=Float.parseFloat(health.getValue()); save.active=enter.active=SleepClient.supported() && minecraft.player!=null && io.github.limuqy.mc.hassium.protocol.sleep.SleepControl.validThreshold(f,minecraft.player.getMaxHealth()); }
        catch(NumberFormatException e) { save.active=enter.active=false; }
    }
    @Override public void render(GuiGraphics g,int x,int y,float delta) {
        super.render(g,x,y,delta);
        g.drawCenteredString(font,title,width/2,height/2-100,0xffffff);
        g.drawCenteredString(font,Component.translatable("hassium.sleep.threshold"),width/2,height/2-79,0xdddddd);
        g.drawCenteredString(font,Component.translatable("hassium.sleep.threshold_help"),width/2,height/2-40,0xaaaaaa);
        try { float hp=Float.parseFloat(health.getValue()); g.drawCenteredString(font,Component.translatable("hassium.sleep.hearts",hp,hp/2),width/2,height/2-26,0xaaaaaa); }
        catch(NumberFormatException ignored) { }
        g.drawCenteredString(font,Component.translatable("hassium.sleep.auto_help"),width/2,height/2+100,0xaaaaaa);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
