package io.github.limuqy.mc.hassium.client.sleep;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

public final class SleepPanelScreen extends Screen {
    public SleepPanelScreen() { super(Component.translatable("hassium.sleep.title")); }
    @Override protected void init() {
        var restore=addRenderableWidget(Button.builder(Component.translatable("hassium.sleep.restore"),b->SleepClient.wake()).bounds(width/2-100,height/2-10,200,20).build());
        restore.active=SleepClient.sleeping();
        boolean rejected=SleepClient.state()==SleepClient.State.IDLE;
        var stay=addRenderableWidget(Button.builder(Component.translatable(rejected ? "menu.returnToGame" : "hassium.sleep.continue"),b->{
            if(SleepClient.state()==SleepClient.State.IDLE) minecraft.setScreen(null); else SleepClient.continueSleep();
        }).bounds(width/2-100,height/2+16,200,20).build());
        stay.active=rejected || SleepClient.state()==SleepClient.State.SLEEPING;
        addRenderableWidget(Button.builder(Component.translatable("menu.disconnect"),b->SleepClient.disconnect()).bounds(width/2-100,height/2+42,200,20).build());
    }
    @Override public void render(GuiGraphics g,int x,int y,float delta) {
        g.fill(0,0,width,height,0xff10151b); super.render(g,x,y,delta);
        g.drawCenteredString(font,title,width/2,height/2-65,0xffffff);
        g.drawCenteredString(font,SleepClient.detail(),width/2,height/2-36,0xffcccc);
    }
    @Override public boolean isPauseScreen() { return false; }
    @Override public void onClose() { if(SleepClient.state()==SleepClient.State.SLEEPING) SleepClient.continueSleep(); else minecraft.setScreen(null); }
}
