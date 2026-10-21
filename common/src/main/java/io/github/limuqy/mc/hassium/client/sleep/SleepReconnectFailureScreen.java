package io.github.limuqy.mc.hassium.client.sleep;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.network.chat.Component;

public final class SleepReconnectFailureScreen extends Screen {
    private final Screen reason; private final ServerData target; private final long deadline;
    public SleepReconnectFailureScreen(Screen reason,ServerData target,long deadline) {
        super(Component.translatable("hassium.sleep.retry_title")); this.reason=reason;this.target=target;this.deadline=deadline;
    }
    @Override protected void init() {
        var retry=addRenderableWidget(Button.builder(Component.translatable("hassium.sleep.retry"),b->{
            SleepClient.allowManualConnection(); io.github.limuqy.mc.hassium.compat.SleepClientCompat.connect(this,target);
        }).bounds(width/2-100,height/2,200,20).build()); retry.active=target!=null;
        addRenderableWidget(Button.builder(Component.translatable("gui.back"),b->onClose()).bounds(width/2-100,height/2+26,200,20).build());
    }
    @Override public void render(GuiGraphics g,int x,int y,float delta) {
        g.fill(0,0,width,height,0xff10151b); super.render(g,x,y,delta);
        g.drawCenteredString(font,title,width/2,height/2-55,0xffffff);
        long seconds=Math.max(0,(deadline-System.nanoTime())/1_000_000_000L);
        g.drawCenteredString(font,Component.translatable("hassium.sleep.hold_remaining",seconds),width/2,height/2-30,0xdddddd);
    }
    @Override public void onClose() { minecraft.setScreen(reason); }
}
