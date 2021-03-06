package codechicken.multipart

import net.minecraft.tileentity.TileEntity
import scala.collection.immutable.Map
import scala.reflect.runtime.universe._
import scala.tools.reflect.ToolBox
import net.minecraft.world.World
import codechicken.core.vec.BlockCoord
import codechicken.multipart.handler.MultipartProxy
import cpw.mods.fml.common.FMLCommonHandler
import cpw.mods.fml.relauncher.Side
import codechicken.core.packet.PacketCustom
import net.minecraft.network.packet.Packet53BlockChange

object MultipartGenerator
{
    private var ugenid = 0
    
    var mirror = scala.reflect.runtime.currentMirror
    var tb = mirror.mkToolBox()
    
    def Apply(f:Tree, args:Tree*) = scala.reflect.runtime.universe.Apply(f, List(args:_*))
    def Apply(f:Tree, args:List[Tree]) = scala.reflect.runtime.universe.Apply(f, args)
    def Invoke(s:Tree, n:TermName, args:Tree*) = Apply(Select(s, n), args:_*)
    def literalUnit = Literal(Constant(()))
    def PkgIdent(s:String):Tree = Ident(mirror.staticClass(s))
    
    def getType(obj:Any) = mirror.classSymbol(obj.getClass).toType
    
    def defaultConstructor() = 
        DefDef(
            Modifiers(), 
            nme.CONSTRUCTOR, //method name
            List(), //type params
            List(List()), //params
            TypeTree(), //return type
            Block(
                Invoke(//body
                    Super(This(tpnme.EMPTY), tpnme.EMPTY), 
                    nme.CONSTRUCTOR
                )
            )
        )
    
    def normalClassDef(mods:FlagSet, name:String, parents:List[String], methods:List[Tree]) = 
        ClassDef(
            Modifiers(mods),
            name,
            List(),
            Template(
                parents.map(PkgIdent(_)),
                emptyValDef,
                methods
            )
        )
    
    def uniqueName(prefix:String):String = {
        val ret = prefix+"$$"+ugenid
        ugenid += 1
        return ret
    }
    
    abstract class Generator
    {
        def generate():TileMultipart
    }
    
    object SuperSet
    {
        val TileMultipartType = typeOf[TileMultipart]
        val TileMultipartClientType = typeOf[TileMultipartClient]
        def apply(types:Seq[Type], client:Boolean) = new SuperSet(types, client)
    }
    
    class SuperSet(types:Seq[Type], client:Boolean)
    {
        import SuperSet._
        val set = baseType+:types.sortWith(_.toString < _.toString)
        
        def interfaces = set
        def baseType = if(client) TileMultipartClientType else TileMultipartType
        
        override def equals(obj:Any) = obj match
        {
            case x:SuperSet => set == x.set
            case _ => false
        }
        
        override def hashCode() = set.hashCode
        
        def generate():TileMultipart = 
        {
            return generatorMap.getOrElse(this, gen_sync).generate
        }
        
        def gen_sync():Generator = tb.synchronized
        {
            return generatorMap.getOrElse(this, {
                var gen = generator
                generatorMap = generatorMap+(this->gen)
                gen
            })
        }
        
        def generator():Generator = 
        {
            val s = System.currentTimeMillis
            val defClass = 
                normalClassDef(
                    NoFlags,
                    uniqueName("TileMultipart_cmp"),
                    set.map(_.typeSymbol.fullName).toList, 
                    List(
                        defaultConstructor
                    )
                )
            val defGenClass = 
                normalClassDef(
                    Flag.FINAL, 
                    uniqueName("TileMultipart_gen"), 
                    List("codechicken.multipart.MultipartGenerator.Generator"), 
                    List(//methods
                        defaultConstructor, 
                        DefDef(
                            Modifiers(Flag.OVERRIDE), 
                            "generate":TermName, 
                            List(), 
                            List(List()), 
                            TypeTree(), 
                            Invoke(
                                New(Ident(defClass.name)), 
                                nme.CONSTRUCTOR
                            )
                        )
                    )
                )
            val constructGenClass = 
                Invoke(//return new generator instance
                    New(Ident(defGenClass.name)), 
                    nme.CONSTRUCTOR
                )
            
            val v = tb.eval(Block(defClass, defGenClass, constructGenClass)).asInstanceOf[Generator]
            val dummy = v.generate
            tileTraitMap=tileTraitMap+(dummy.getClass->types.toSet)
            MultipartProxy.onTileClassBuilt(dummy.getClass)
            println("Generation ["+types.mkString(", ")+"] took: "+(System.currentTimeMillis-s))
            return v.asInstanceOf[Generator]
        }
    }
    
    private var generatorMap:Map[SuperSet, Generator] = Map()
    private var tileTraitMap:Map[Class[_], Set[Type]] = Map()
    private var interfaceTraitMap_c:Map[Type, Seq[Type]] = Map()
    private var interfaceTraitMap_s:Map[Type, Seq[Type]] = Map()
    private var partTraitMap_c:Map[Class[_], Seq[Type]] = Map()
    private var partTraitMap_s:Map[Class[_], Seq[Type]] = Map()
    
    SuperSet(Seq(), false).generate//default impl, boots generator
    if(FMLCommonHandler.instance.getEffectiveSide == Side.CLIENT)
        SuperSet(Seq(), true).generate
    
    private def partTraitMap(client:Boolean) = if(client) partTraitMap_c else partTraitMap_s
    
    private def interfaceTraitMap(client:Boolean) = if(client) partTraitMap_c else interfaceTraitMap_s
        
    private def traitsForPart(part:TMultiPart, client:Boolean):Seq[Type] = 
    {
        var ret = partTraitMap(client).getOrElse(part.getClass, null)
        if(ret == null)
        {
            if(client)
            {
                ret = getType(part).baseClasses.flatMap(s => interfaceTraitMap_c.getOrElse(s.asClass.toType, List())).distinct
                partTraitMap_c = partTraitMap_c+(part.getClass -> ret)
            }
            else
            {
                ret = getType(part).baseClasses.flatMap(s => interfaceTraitMap_s.getOrElse(s.asClass.toType, List())).distinct
                partTraitMap_s = partTraitMap_s+(part.getClass -> ret)
            }
        }
        return ret
    }
    
    /**
     * Check if part adds any new interfaces to tile, if so, replace tile with a new copy and call tile.addPart(part)
     * returns true if tile was replaced
     */
    private[multipart] def addPart(world:World, pos:BlockCoord, part:TMultiPart):TileMultipart =
    {
        var loaded = TileMultipartObj.getOrConvertTile2(world, pos)
        var partTraits = traitsForPart(part, world.isRemote)
        val tile = loaded._1
        var ntile = tile
        if(ntile != null)
        {
            if(loaded._2)//perform client conversion
            {
                world.setBlock(pos.x, pos.y, pos.z, MultipartProxy.block.blockID, 0, 1)
                world.setBlockTileEntity(pos.x, pos.y, pos.z, ntile)
                PacketCustom.sendToChunk(new Packet53BlockChange(pos.x, pos.y, pos.z, world), world, pos.x>>4, pos.z>>4)
                ntile.partList(0).onConverted()
                ntile.writeAddPart(ntile.partList(0))
            }
            
            val tileTraits = tileTraitMap(tile.getClass)
            partTraits = partTraits.filter(!tileTraits(_))
            if(!partTraits.isEmpty)
            {
                ntile = SuperSet(partTraits++tileTraits, world.isRemote).generate
                world.setBlockTileEntity(pos.x, pos.y, pos.z, ntile)
                ntile.loadFrom(tile)
            }
        }
        else
        {
            world.setBlock(pos.x, pos.y, pos.z, MultipartProxy.block.blockID)
            ntile = SuperSet(partTraits, world.isRemote).generate
            world.setBlockTileEntity(pos.x, pos.y, pos.z, ntile)
        }
        ntile.addPart(part)
        return ntile
    }
    
    /**
     * Check if tile satisfies all the interfaces required by parts. If not, return a new generated copy of tile
     */
    def generateCompositeTile(tile:TileEntity, parts:Seq[TMultiPart], client:Boolean):TileMultipart = 
    {
        var partTraits = parts.flatMap(traitsForPart(_, client)).distinct
        if(tile != null && tile.isInstanceOf[TileMultipart])
        {
            var tileTraits = tileTraitMap(tile.getClass)
            if(partTraits.forall(tileTraits(_)) && partTraits.size == tileTraits.size)//equal contents
                return tile.asInstanceOf[TileMultipart]
            
        }
        return SuperSet(partTraits, client).generate
    }
    
    /**
     * Check if there are any redundant interfaces on tile, if so, replace tile with new copy
     */
    def partRemoved(tile:TileMultipart, part:TMultiPart):TileMultipart = 
    {
        val client = tile.worldObj.isRemote
        var partTraits = tile.partList.flatMap(traitsForPart(_, client))
        var testSet = partTraits.toSet
        if(!traitsForPart(part, client).forall(testSet(_)))
        {
            val ntile = SuperSet(testSet.toSeq, client).generate
            tile.worldObj.setBlockTileEntity(tile.xCoord, tile.yCoord, tile.zCoord, ntile)
            ntile.loadFrom(tile)
            return ntile
        }
        return tile
    }
    
    /**
     * register s_trait to be applied to tiles containing parts implementing s_interface
     */
    def registerTrait(s_interface:String, s_trait:String):Unit = registerTrait(s_interface, s_trait, s_trait)
    
    /**
     * register traits to be applied to tiles containing parts implementing s_interface
     * s_trait for server worlds (may be null)
     * c_trait for client worlds (may be null)
     */
    def registerTrait(s_interface:String, c_trait:String, s_trait:String)
    {
            val iSymbol = mirror.staticClass(s_interface).asClass
        if(c_trait != null)
        {
            val tSymbol = mirror.staticClass(c_trait).asClass
            //TODO some checking
            var reg = interfaceTraitMap_c.getOrElse(iSymbol.toType, Seq())
            if(!reg.contains(tSymbol.toType))
                reg = reg:+tSymbol.toType
            interfaceTraitMap_c = interfaceTraitMap_c+(iSymbol.toType->reg)
        }
        if(s_trait != null)
        {
            val tSymbol = mirror.staticClass(s_trait).asClass
            //TODO some checking
            var reg = interfaceTraitMap_s.getOrElse(iSymbol.toType, Seq())
            if(!reg.contains(tSymbol.toType))
                reg = reg:+tSymbol.toType
            interfaceTraitMap_s = interfaceTraitMap_s+(iSymbol.toType->reg)
        }
    }
}