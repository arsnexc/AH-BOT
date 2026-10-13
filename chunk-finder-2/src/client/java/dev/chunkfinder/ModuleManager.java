package dev.chunkfinder;

import java.util.List;

public final class ModuleManager {
    private final ChunkFinderModule chunkFinder = new ChunkFinderModule();
    private final AncientDebrisFinderModule ancientDebrisFinder = new AncientDebrisFinderModule();
    private final ItemEspModule itemEsp = new ItemEspModule();
    private final StorageEspModule storageEsp = new StorageEspModule();
    private final TrajectoriesModule trajectories = new TrajectoriesModule();
    private final TargetRendererModule targetRenderer = new TargetRendererModule();
    private final FreecamModule freecam = new FreecamModule();
    private final List<Module> modules = List.of(chunkFinder, ancientDebrisFinder, itemEsp,
            storageEsp, trajectories, targetRenderer, freecam);

    public List<Module> modules() { return modules; }
    public ChunkFinderModule chunkFinder() { return chunkFinder; }
    public AncientDebrisFinderModule ancientDebrisFinder() { return ancientDebrisFinder; }
    public ItemEspModule itemEsp() { return itemEsp; }
    public StorageEspModule storageEsp() { return storageEsp; }
    public TrajectoriesModule trajectories() { return trajectories; }
    public TargetRendererModule targetRenderer() { return targetRenderer; }
    public FreecamModule freecam() { return freecam; }
}
