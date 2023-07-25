package org.daimhim.imc_core.demo

import android.view.LayoutInflater
import android.view.ViewGroup
import org.daimhim.imc_core.demo.databinding.MainAdapterItemBinding
import org.daimhim.widget.sa.SimpleRVAdapter
import org.daimhim.widget.sa.SimpleViewHolder
import java.util.Date


class MainAdapter : SimpleRVAdapter() {
    private val data = mutableListOf<MainItem>()

    fun clear(){
        data.clear()
        notifyDataSetChanged()
    }
    fun addItem(item:MainItem){
        data.add(0,item)
        notifyItemInserted(0)
    }
    fun getItem(position: Int):MainItem{
        return data.get(position)
    }
    override fun getItemCount(): Int {
        return data.size
    }

    override fun onCreateDataViewHolder(parent: ViewGroup, viewType: Int): SimpleViewHolder {
        return SimpleViewHolder(LayoutInflater.from(parent.context)
            .inflate(R.layout.main_adapter_item,parent, false))
    }

    override fun onBindDataViewHolder(holder: SimpleViewHolder, position: Int) {
        val bind = MainAdapterItemBinding.bind(holder.itemView)
        val item = getItem(position)
        bind.clRootLayout.layoutDirection = Math.abs(item.type)
        bind.textView.setText(item.name)
        bind.textView2.setText(item.content)
        bind.time.setText(Date(item.time).toString())
    }
}