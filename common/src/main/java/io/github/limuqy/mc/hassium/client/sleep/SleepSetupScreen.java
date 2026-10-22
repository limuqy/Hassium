package io.github.limuqy.mc.hassium.client.sleep;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class SleepSetupScreen extends Screen {
    private final Screen parent;
    private EditBox minutes;
    private Button enter,save;
    public SleepSetupScreen(Screen parent) { super(Component.translatable("hassium.sleep.title")); this.parent=parent; }
    @Override protected void init() {
        String value=minutes==null ? Integer.toString(SleepProfiles.autoSeconds()/60) : minutes.getValue();
        minutes=new EditBox(font,width/2-70,height/2-40,140,20,Component.translatable("hassium.sleep.auto_minutes"));
        minutes.setMaxLength(2); minutes.setValue(value); addRenderableWidget(minutes);
        save=addRenderableWidget(Button.builder(Component.translatable("hassium.sleep.save"),b->{
            if(savePreferences()) minecraft.setScreen(null);
        }).bounds(width/2-100,height/2-5,200,20).build());
        enter=addRenderableWidget(Button.builder(Component.translatable("hassium.sleep.enter"),b->{
            if(savePreferences()) SleepClient.enter();
        }).bounds(width/2-100,height/2+21,200,20).build());
        addRenderableWidget(Button.builder(Component.translatable("gui.cancel"),b->onClose()).bounds(width/2-100,height/2+47,200,20).build());
    }
    private int autoSeconds() {
        int value=Integer.parseInt(minutes.getValue());
        if(value<0 || value>60) throw new IllegalArgumentException("Auto sleep minutes must be 0..60");
        return value*60;
    }
    private boolean savePreferences() {
        try { SleepProfiles.save(autoSeconds()); return true; }
        catch(IllegalArgumentException e) { return false; }
    }
    @Override public void tick() {
        try { autoSeconds(); save.active=enter.active=SleepClient.supported() && minecraft.player!=null; }
        catch(IllegalArgumentException e) { save.active=enter.active=false; }
    }
    @Override public void render(GuiGraphics g,int x,int y,float delta) {
        super.render(g,x,y,delta);
        g.drawCenteredString(font,title,width/2,height/2-90,0xffffff);
        g.drawCenteredString(font,Component.translatable("hassium.sleep.auto_minutes"),width/2,height/2-58,0xdddddd);
        g.drawCenteredString(font,Component.translatable("hassium.sleep.auto_help"),width/2,height/2+80,0xaaaaaa);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { minecraft.setScreen(parent); }
}
