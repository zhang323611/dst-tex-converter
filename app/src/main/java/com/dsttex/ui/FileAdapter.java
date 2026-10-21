package com.dsttex.ui;

import android.content.res.ColorStateList;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.dsttex.R;
import com.dsttex.databinding.ItemFileBinding;
import com.dsttex.core.TexCodec;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 文件列表适配器：ViewHolder + 预取元数据，选中态用 activated 背景（不重建行） */
public final class FileAdapter extends RecyclerView.Adapter<FileAdapter.VH> {

    public interface Listener {
        void onClick(int position, FileEntry entry);

        boolean onLongClick(int position, FileEntry entry);
    }

    private final List<FileEntry> items = new ArrayList<>();
    private final Set<Integer> selected = new HashSet<>();
    private final Listener listener;
    private final SimpleDateFormat dateFmt = new SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault());

    private final int cDir, cTex, cPng, cZip, cFile;
    private final String folderLabel, folderFmt;

    public FileAdapter(Listener l, int cDir, int cTex, int cPng, int cZip, int cFile,
                       String folderLabel, String folderFmt) {
        this.listener = l;
        this.cDir = cDir;
        this.cTex = cTex;
        this.cPng = cPng;
        this.cZip = cZip;
        this.cFile = cFile;
        this.folderLabel = folderLabel;
        this.folderFmt = folderFmt;
        setHasStableIds(false);
    }

    public void submit(List<FileEntry> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
    }

    public FileEntry get(int position) {
        return items.get(position);
    }

    public int size() {
        return items.size();
    }

    public Set<Integer> selectedPositions() {
        return selected;
    }

    public boolean isSelected(int position) {
        return selected.contains(position);
    }

    public void setSelected(int position, boolean on) {
        if (on) selected.add(position);
        else selected.remove(position);
        notifyItemChanged(position);
    }

    public void clearSelection() {
        if (selected.isEmpty()) return;
        selected.clear();
        notifyDataSetChanged();
    }

    public void selectAll(boolean on) {
        selected.clear();
        if (on) for (int i = 0; i < items.size(); i++) selected.add(i);
        notifyDataSetChanged();
    }

    public List<FileEntry> selectedFilesOnly() {
        List<FileEntry> out = new ArrayList<>();
        for (Integer i : new ArrayList<>(selected)) {
            if (i >= 0 && i < items.size() && !items.get(i).dir) out.add(items.get(i));
        }
        return out;
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        ItemFileBinding b = ItemFileBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false);
        return new VH(b);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        FileEntry e = items.get(position);
        String letter;
        int color;
        if (e.dir) {
            letter = "D";
            color = cDir;
        } else if (e.lower.endsWith(".tex")) {
            letter = "T";
            color = cTex;
        } else if (e.lower.endsWith(".png")) {
            letter = "P";
            color = cPng;
        } else if (e.lower.endsWith(".zip")) {
            letter = "Z";
            color = cZip;
        } else {
            letter = "F";
            color = cFile;
        }
        h.badge.setText(letter);
        h.badge.setBackgroundTintList(ColorStateList.valueOf(color));
        h.name.setText(e.name);
        if (e.dir) {
            h.sub.setText(e.childCount < 0 ? folderLabel : String.format(folderFmt, e.childCount));
        } else {
            h.sub.setText(TexCodec.humanSize(e.size) + " · " + dateFmt.format(new Date(e.mtime)));
        }
        h.binding.getRoot().setActivated(selected.contains(position));
        h.binding.getRoot().setOnClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            if (pos != RecyclerView.NO_POSITION && listener != null) listener.onClick(pos, items.get(pos));
        });
        h.binding.getRoot().setOnLongClickListener(v -> {
            int pos = h.getBindingAdapterPosition();
            return pos != RecyclerView.NO_POSITION && listener != null
                    && listener.onLongClick(pos, items.get(pos));
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static final class VH extends RecyclerView.ViewHolder {
        final ItemFileBinding binding;
        final TextView badge, name, sub;

        VH(ItemFileBinding b) {
            super(b.getRoot());
            binding = b;
            badge = b.badge;
            name = b.name;
            sub = b.sub;
        }
    }
}
