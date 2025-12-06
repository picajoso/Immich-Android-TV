package nl.giejay.android.tv.immich.settings

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.fragment.app.Fragment
import androidx.leanback.app.BrowseSupportFragment
import androidx.leanback.widget.VerticalGridView
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.RecyclerView
import nl.giejay.android.tv.immich.R
import nl.giejay.android.tv.immich.home.HomeFragmentDirections
import nl.giejay.android.tv.immich.shared.prefs.DebugPrefScreen
import nl.giejay.android.tv.immich.shared.prefs.ScreensaverPrefScreen
import nl.giejay.android.tv.immich.shared.prefs.ViewPrefScreen

// Modelo de datos para cada fila
data class NewSettingsItem(
    val title: String,
    val description: String,
    val iconName: String, 
    val onClick: () -> Unit
)

class SettingsFragment : Fragment(), BrowseSupportFragment.MainFragmentAdapterProvider {

    // Creamos el adaptador pasándole 'this' (este fragmento)
    private val mMainFragmentAdapter = BrowseSupportFragment.MainFragmentAdapter(this)

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_settings_custom, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val gridView = view.findViewById<VerticalGridView>(R.id.settings_list)
        gridView.setNumColumns(1)
        gridView.windowAlignment = VerticalGridView.WINDOW_ALIGN_NO_EDGE
        gridView.windowAlignmentOffsetPercent = 15f 

        val items = listOf(
            NewSettingsItem("Server", "Configure server connection", "ic_settings_settings") {
                findNavController().navigate(HomeFragmentDirections.actionGlobalSignInFragment())
            },
            NewSettingsItem("View settings", "Customize the interface", "icon_view") {
                findNavController().navigate(HomeFragmentDirections.actionGlobalToSettingsDialog(ViewPrefScreen.key))
            },
            NewSettingsItem("Screensaver", "Manage screensaver options", "screensaver") {
                findNavController().navigate(HomeFragmentDirections.actionGlobalToSettingsDialog(ScreensaverPrefScreen.key))
            },
            NewSettingsItem("Debug", "Access debugging tools", "bug") {
                findNavController().navigate(HomeFragmentDirections.actionGlobalToSettingsDialog(DebugPrefScreen.key))
            },
            NewSettingsItem("Donate", "Support the project", "donate") {
                findNavController().navigate(HomeFragmentDirections.actionHomeToDonate())
            }
        )

        gridView.adapter = SettingsAdapter(items)
        gridView.requestFocus()
    }

    // CORRECCIÓN AQUÍ: Añadido <SettingsFragment> para que el compilador sea feliz
    override fun getMainFragmentAdapter(): BrowseSupportFragment.MainFragmentAdapter<SettingsFragment> {
        return mMainFragmentAdapter
    }

    inner class SettingsAdapter(private val items: List<NewSettingsItem>) :
        RecyclerView.Adapter<SettingsAdapter.ViewHolder>() {

        inner class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
            val icon: ImageView = view.findViewById(R.id.icon_start)
            val title: TextView = view.findViewById(R.id.text_title)
            val description: TextView = view.findViewById(R.id.text_description)

            fun bind(item: NewSettingsItem) {
                title.text = item.title
                description.text = item.description

                val context = itemView.context
                val resId = context.resources.getIdentifier(item.iconName, "drawable", context.packageName)
                
                if (resId != 0) {
                    icon.setImageResource(resId)
                } else {
                    icon.setImageResource(android.R.drawable.ic_menu_preferences)
                }

                itemView.setOnClickListener { item.onClick() }
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_settings, parent, false)
            return ViewHolder(view)
        }

        override fun onBindViewHolder(holder: ViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount() = items.size
    }
}
