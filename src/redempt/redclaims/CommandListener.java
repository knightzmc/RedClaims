package redempt.redclaims;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import redempt.redclaims.claim.Claim;
import redempt.redclaims.claim.ClaimMap;
import redempt.redclaims.claim.ClaimRank;
import redempt.redclaims.claim.ClaimStorage;
import redempt.redclaims.claim.Subclaim;
import redempt.redlib.commandmanager.ArgType;
import redempt.redlib.commandmanager.CommandHook;
import redempt.redlib.commandmanager.CommandParser;
import redempt.redlib.commandmanager.ContextProvider;
import redempt.redlib.commandmanager.Messages;
import redempt.redlib.misc.Task;
import redempt.redlib.misc.UserCache;
import redempt.redlib.region.CuboidRegion;

import java.util.Arrays;
import java.util.Map;
import java.util.stream.Collectors;

public class CommandListener {
	
	private RedClaims plugin;
	private ClaimTool tool;
	
	public CommandListener(RedClaims plugin) {
		this.plugin = plugin;
	}
	
	public void register() {
		ClaimStorage storage = plugin.getClaimStorage();
		ArgType<Claim> claimType = new ArgType<>("claim", (c, s) -> {
			int index = s.indexOf(':');
			if (index == -1) {
				if (!(c instanceof Player)) {
					return null;
				}
				Player player = (Player) c;
				return storage.getClaim(player.getUniqueId(), s);
			}
			String playerName = s.substring(0, index);
			String claimName = s.substring(index + 1);
			OfflinePlayer player = UserCache.getOfflinePlayer(playerName);
			if (player == null) {
				return null;
			}
			return storage.getClaim(player.getUniqueId(), claimName);
		}).tabStream((c, s) -> {
			String last = s[s.length - 1];
			int index = last.indexOf(':');
			if (index == -1) {
				if (!(c instanceof Player)) {
					return null;
				}
				Player player = (Player) c;
				Map<String, Claim> claims = storage.getClaims(player.getUniqueId());
				if (claims == null) {
					return null;
				}
				return claims.keySet().stream();
			}
			String playerName = last.substring(0, index);
			OfflinePlayer player = UserCache.getOfflinePlayer(playerName);
			if (player == null) {
				return null;
			}
			Map<String, Claim> claims = storage.getClaims(player.getUniqueId());
			if (claims == null) {
				return null;
			}
			return claims.keySet().stream().map(n -> player.getName() + ":" + n);
		});
		
		ArgType<Subclaim> subclaimType = claimType.subType("subclaim", (s, c) -> c.getSubclaim(s))
				.tabStream((s, c, a) -> c.getSubclaims().stream().map(Subclaim::getName));
		ArgType<ClaimFlag> flagType = ArgType.of("flag", ClaimFlag.BY_NAME);
		ArgType<OfflinePlayer> userType = new ArgType<>("user", UserCache::getOfflinePlayer).tabStream(c -> Bukkit.getOnlinePlayers().stream().map(Player::getName));
		ArgType<ClaimRank> rankType = new ArgType<>("role", s -> ClaimRank.valueOf(s.toUpperCase())).tabStream(c -> Arrays.stream(ClaimRank.values()).map(r -> r.name().toLowerCase()));
		
		ContextProvider<CuboidRegion> selectionProvider = new ContextProvider<>("selection", Messages.msg("noSelection"), c -> tool.getSelection(c.getUniqueId()));
		ContextProvider<Claim> currentClaimProvider = new ContextProvider<>("currentClaim", Messages.msg("notInClaim"), c -> ClaimMap.getClaim(c.getLocation()));
		
		tool = new ClaimTool(plugin, new ItemStack(plugin.getClaimToolMaterial()));
		
		new CommandParser(plugin.getResource("command.rdcml"))
				.setArgTypes(claimType, subclaimType, flagType, userType, rankType)
				.setContextProviders(selectionProvider, currentClaimProvider)
				.parse().register("redclaims", this);
	}
	
	@CommandHook("createClaim")
	public void createClaim(Player sender, String name, CuboidRegion selection) {
		if (name.length() > 16) {
			sender.sendMessage(Messages.msg("claimNameTooLong"));
			return;
		}
		Location start = selection.getStart();
		Location end = selection.getEnd();
		start.setY(end.getWorld().getMinHeight());
		end.setY(end.getWorld().getMaxHeight());
		selection = new CuboidRegion(start, end);
		try {
			Claim claim = plugin.getClaimStorage().createClaim(sender, name, selection);
			sender.sendMessage(Messages.msg("claimCreated"));
			tool.clearSelection(sender.getUniqueId());
			Task.syncDelayed(() -> claim.visualize(sender, true), 1);
		} catch (IllegalArgumentException e) {
			sender.sendMessage(Messages.msg("errorColor") + e.getMessage());
		}
	}
	
	@CommandHook("createSubclaim")
	public void createSubclaim(Player sender, Claim claim, String name, CuboidRegion selection) {
		if (name.length() > 16) {
			sender.sendMessage(Messages.msg("claimNameTooLong"));
			return;
		}
		if (!claim.hasAtLeast(sender, ClaimRank.OWNER)) {
			sender.sendMessage(Messages.msg("notOwner"));
			return;
		}
		if (Arrays.stream(selection.getBlockDimensions()).anyMatch(i -> i < 3)) {
			sender.sendMessage(Messages.msg("subclaimTooSmall"));
			return;
		}
		try {
			claim.createSubclaim(name, selection);
			sender.sendMessage(Messages.msg("subclaimCreated"));
			tool.clearSelection(sender.getUniqueId());
			Task.syncDelayed(() -> claim.visualize(sender, true), 1);
		} catch (IllegalArgumentException e) {
			sender.sendMessage(Messages.msg("errorColor") + e.getMessage());
		}
	}
	
	@CommandHook("addSubclaimFlag")
	public void addSubclaimFlags(CommandSender sender, Claim claim, Subclaim subclaim, ClaimFlag[] flags) {
		if (!claim.hasAtLeast(sender, ClaimRank.OWNER)) {
			sender.sendMessage(Messages.msg("notOwner"));
			return;
		}
		subclaim.addFlag(flags);
		sender.sendMessage(Messages.msg("protectionAdded"));
	}
	
	@CommandHook("removeSubclaimFlag")
	public void removeSubclaimFlags(CommandSender sender, Claim claim, Subclaim subclaim, ClaimFlag[] flags) {
		if (!claim.hasAtLeast(sender, ClaimRank.OWNER)) {
			sender.sendMessage(Messages.msg("notOwner"));
			return;
		}
		subclaim.removeFlag(flags);
		sender.sendMessage(Messages.msg("protectionRemoved"));
	}
	
	@CommandHook("claimInfo")
	public void claimInfo(CommandSender sender, Claim claim) {
		sender.sendMessage(Messages.msg("claimInfoHeader").replace("%name%", claim.getOwner().getName() + ":" + claim.getName()));
		String primary = Messages.msg("primaryColor");
		String secondary = Messages.msg("secondaryColor");
		ClaimStorage storage = plugin.getClaimStorage();
		sender.sendMessage(Messages.msg("claimBlocks").replace("%blocks%", storage.getClaimBlocks(claim) + ""));
		sender.sendMessage(Messages.msg("claimFlags").replace("%flags%", claim.getFlags().stream().map(f -> secondary + f.getName()).collect(Collectors.joining(primary + ", "))));
		sender.sendMessage(Messages.msg("claimMembers").replace("%members%", claim.getAllMembers().entrySet().stream()
				.filter(e -> e.getValue() == ClaimRank.MEMBER)
				.map(e -> secondary + Bukkit.getOfflinePlayer(e.getKey()).getName())
				.collect(Collectors.joining(primary + ", "))));
		sender.sendMessage(Messages.msg("claimTrusted").replace("%members%", claim.getAllMembers().entrySet().stream()
				.filter(e -> e.getValue() == ClaimRank.TRUSTED)
				.map(e -> secondary + Bukkit.getOfflinePlayer(e.getKey()).getName())
				.collect(Collectors.joining(primary + ", "))));
	}
	
	@CommandHook("deleteClaim")
	public void deleteClaim(CommandSender sender, Claim claim) {
		if (!claim.hasAtLeast(sender, ClaimRank.OWNER)) {
			sender.sendMessage(Messages.msg("notOwner"));
			return;
		}
		plugin.getClaimStorage().deleteClaim(claim);
		sender.sendMessage(Messages.msg("claimDeleted"));
	}
	
	@CommandHook("setRole")
	public void setRole(CommandSender sender, Claim claim, OfflinePlayer user, ClaimRank rank) {
		if (user.equals(claim.getOwner())) {
			sender.sendMessage(Messages.msg("cannotSetOwnerRole"));
			return;
		}
		if (!sender.hasPermission("redclaims.admin")) {
			switch (rank) {
				case VISITOR:
				case MEMBER:
					if (!claim.hasAtLeast(sender, ClaimRank.TRUSTED)
							|| (sender instanceof Player && claim.getRank(user).getRank() >= claim.getRank((Player) sender).getRank())) {
						sender.sendMessage(Messages.msg("insufficientPermission"));
						return;
					}
					break;
				case TRUSTED:
					if (!claim.hasAtLeast(sender, ClaimRank.OWNER)) {
						sender.sendMessage(Messages.msg("insufficientPermission"));
						return;
					}
					break;
			}
		}
		try {
			claim.setRank(user, rank);
			sender.sendMessage(Messages.msg("roleSet"));
		} catch (IllegalArgumentException e) {
			sender.sendMessage(Messages.msg("cannotTransferOwnership"));
		}
	}
	
	@CommandHook("addClaimFlag")
	public void addClaimFlag(CommandSender sender, Claim claim, ClaimFlag[] flags) {
		if (!claim.hasAtLeast(sender, ClaimRank.OWNER)) {
			sender.sendMessage(Messages.msg("notOwner"));
			return;
		}
		claim.addFlag(flags);
		sender.sendMessage(Messages.msg("protectionAdded"));
	}
	
	@CommandHook("removeClaimFlag")
	public void removeClaimFlag(CommandSender sender, Claim claim, ClaimFlag[] flags) {
		if (!claim.hasAtLeast(sender, ClaimRank.OWNER)) {
			sender.sendMessage(Messages.msg("notOwner"));
			return;
		}
		claim.removeFlag(flags);
		sender.sendMessage(Messages.msg("protectionRemoved"));
	}
	
	@CommandHook("currentClaimInfo")
	public void currentClaimInfo(Player player, Claim claim) {
		claimInfo(player, claim);
	}
	
	@CommandHook("renameClaim")
	public void renameClaim(CommandSender sender, Claim claim, String name) {
		if (!claim.hasAtLeast(sender, ClaimRank.OWNER)) {
			sender.sendMessage(Messages.msg("notOwner"));
			return;
		}
		try {
			plugin.getClaimStorage().renameClaim(claim, name);
			sender.sendMessage(Messages.msg("claimRenamed"));
		}catch (IllegalArgumentException e) {
			sender.sendMessage(Messages.msg("errorColor") + e.getMessage());
		}
	}
	
	@CommandHook("visualize")
	public void visualize(Player player, Claim claim) {
		claim.visualize(player, true);
	}
	
	@CommandHook("unvisualize")
	public void unvisualize(Player player, Claim claim) {
		claim.unvisualize(player);
	}
	
	@CommandHook("budget")
	public void budget(CommandSender sender, Player player) {
		int budget = ClaimLimits.getClaimLimit(player);
		int used = plugin.getClaimStorage().getClaimedBlocks(player.getUniqueId());
		sender.sendMessage(Messages.msg("claimBudget").replace("%budget%", used + " / " + budget));
	}
	
	@CommandHook("setBudget")
	public void setBudget(CommandSender sender, Player player, int budget) {
		ClaimLimits.setClaimLimit(player, budget);
		sender.sendMessage(ChatColor.GREEN + "Claim limit set!");
	}
	
	@CommandHook("addBudget")
	public void addBudget(CommandSender sender, Player player, int budget) {
		ClaimLimits.addClaimLimit(player, budget);
		sender.sendMessage(ChatColor.GREEN + "Claim limit set!");
	}
	
	@CommandHook("bypass")
	public void bypass(Player player) {
		player.sendMessage(ChatColor.GREEN + "Claim bypass " + (ClaimBypass.toggle(player.getUniqueId()) ? "enabled" : "disabled") + "!");
	}
	
}
